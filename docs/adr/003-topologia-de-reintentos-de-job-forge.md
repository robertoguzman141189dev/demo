# 003 — El reintento escalonado se republica, no se reencola

- **Fecha:** 2026-09-03
- **Estado:** aceptado
- **Ámbito:** job-forge

## Contexto

`job-forge` existe para demostrar que la resiliencia en la entrega es un problema
distinto al del throughput. Eso exige tres cosas del broker: que un trabajo
fallido espere antes de volver a intentarse, que la espera crezca a cada intento,
y que después de N intentos alguien pueda mirar el mensaje y decidir. Todo ello
sin scheduler externo, sin base de datos y sin hilos dormidos.

Al implementarlo aparecieron tres restricciones reales del broker que el diseño
original no contemplaba. Las tres se verificaron contra RabbitMQ 4.3.5, no contra
la documentación.

### Restricción 1: las colas quorum no aceptan `x-max-priority`

El broker rechaza la declaración: `invalid arg 'x-max-priority' for queue ... of
queue type rabbit_quorum_queue`. Ese argumento pertenece a las colas clásicas,
que viven en un solo nodo.

### Restricción 2: el dead-lettering no permite escalonar

Cuando RabbitMQ dead-letterea un mensaje, el destino sale de los argumentos de la
cola, que son estáticos, y los headers no se pueden modificar por el camino. Un
`nack` lleva siempre al mismo sitio con el mismo TTL. No hay forma de que el
segundo intento espere más que el primero si la decisión la toma el broker.

### Restricción 3: reencolar no agota el límite de entregas

La defensa intuitiva contra el mensaje que siempre falla es rechazarlo pidiendo
reencolado y confiar en `x-delivery-limit`. **No funciona.** Con un `nack` de
reencolado, RabbitMQ 4.x incrementa `x-acquired-count` —cuántas veces alguien
tomó el mensaje— y no el contador de entregas del que depende el límite. Medido:
un mensaje con el límite en 3 sobrevivió a más de 11 000 reencolados sin que la
cola lo cortara jamás, monopolizando el prefetch del worker todo ese tiempo.

## Decisión

**El worker republica; nunca reencola.** Al fallar una tarea, publica el mensaje
al exchange `jobs.retry` con la routing key del tramo que le toca según
`x-attempt`, espera la confirmación del broker y solo entonces acusa el original.
Agotados los tramos, publica a `jobs.dlx` y la tarea queda en `jobs.dead`.

El backoff son tres colas **sin consumidor** —`jobs.retry.5s`, `.30s`, `.2m`—
cada una con su `x-message-ttl` y un dead letter exchange de vuelta a `jobs`. El
broker es el reloj. La condición de salida del bucle es `x-attempt`, que lleva el
worker.

Sobre las tres restricciones:

1. **Prioridad:** la cola sigue siendo quorum y se usa la propiedad `priority` del
   mensaje, que las colas quorum de RabbitMQ 4.x sí respetan, con dos niveles
   efectivos: normal (0) y alta (5).
2. **Escalonamiento:** lo decide el consumidor, por lo dicho arriba.
3. **Límite de entregas:** `x-delivery-limit` se mantiene declarado, pero para lo
   que sí cubre: el worker que muere sin decir nada y pierde la conexión. No es
   la defensa contra fallos de negocio; esa es `x-attempt`.

Además, `jobs.work` lleva `x-dead-letter-strategy: at-least-once` (y el
`x-overflow: reject-publish` que exige), y su `x-message-ttl` vencido lleva a
`jobs.dead`, no a la cadena de reintentos.

## Costo asumido

- **Dos niveles de prioridad en vez de cinco.** Se pierde granularidad. Para un
  panel que distingue "normal" de "urgente" no cambia nada, pero si algún día
  hicieran falta cinco clases habría que volver a colas clásicas y renunciar a la
  replicación, o modelar la prioridad con colas separadas.
- **La lógica de reintento vive en el consumidor, no en la infraestructura.** Un
  worker con un bug puede saltarse el escalonamiento, republicar al tramo
  equivocado o no incrementar `x-attempt` y provocar un bucle infinito. La
  topología sola no lo impide: hace falta la prueba de integración que verifica
  los tiempos, y por eso es obligatoria.
- **`at-least-once` puede duplicar mensajes en la cola muerta** y obliga al broker
  a retener más en memoria, porque no puede olvidar el mensaje hasta confirmar el
  traspaso. Se acepta porque la alternativa es una ventana real de pérdida, y los
  consumidores son idempotentes por obligación.
- **Republicar rompe el orden.** Una tarea reintentada vuelve al final de su nivel
  de prioridad, detrás de las que llegaron mientras esperaba. `job-forge` no
  promete orden, pero conviene no olvidarlo.
- **El TTL de `jobs.work` manda al cementerio trabajos que quizá solo llegaron en
  mal momento.** Si el pool estuvo caído 20 minutos, esas tareas exigen reproceso
  manual desde el panel. Es deliberado: preferimos intervención humana a un
  reencolado ciego contra un sistema que no está atendiendo.
- **Cuatro colas más que operar y vigilar.** Cada una con su métrica y su alerta.

## Alternativas descartadas

- **Reencolar y confiar en `x-delivery-limit`.** Es lo que hace casi todo el
  mundo. No escalona nada y, medido contra 4.3.5, ni siquiera acota los
  reintentos: el mensaje se reencola para siempre. Descartada por no funcionar,
  no por elegancia.
- **El plugin `rabbitmq_delayed_message_exchange`.** Da retraso por mensaje con un
  solo exchange y menos colas. Descartada porque es un plugin de la comunidad, no
  parte de la distribución: añade una dependencia que hay que instalar en cada
  entorno, incluido el cluster, y guarda el estado de los mensajes retrasados en
  Mnesia en un solo nodo, lo que reintroduce el punto único de fallo que las colas
  quorum vinieron a quitar.
- **Una sola cola de espera con TTL por mensaje.** Menos colas y retraso
  arbitrario. Descartada por el bloqueo de cabeza de cola: RabbitMQ solo comprueba
  el vencimiento del mensaje que está en la cabeza, así que un mensaje de 5 s
  detrás de uno de 2 min espera los 2 minutos completos. El bug aparecería en
  producción bajo carga mixta y sería dificilísimo de diagnosticar.
- **Reintentos en memoria dentro del worker,** con `Thread.sleep` o un
  `RetryTemplate`. Es lo más simple de escribir. Descartada porque el reintento
  muere con el proceso: si el pod se reinicia durante la espera, el trabajo
  desaparece. Y mientras espera, ocupa un hilo y un hueco de prefetch.
- **Colas clásicas con `x-max-priority: 5`,** para respetar los cinco niveles
  originales. Descartada porque una cola clásica vive en un nodo: perder ese nodo
  es perder los mensajes, que es exactamente lo contrario de la tesis del demo.

## Consecuencias

- `contracts` es la única fuente de nombres y declaraciones. Ningún módulo escribe
  el nombre de una cola como literal.
- `RetryRouting` decide el destino y es una función pura, sin Spring y sin broker:
  el worker de F2 la usa, y se puede probar sin levantar nada.
- El orden de operaciones del worker no es negociable: publicar, confirmar, acusar.
- Los tiempos declarados (5 s, 30 s, 2 min) se verifican en `TopologyDeclarationIT`
  con los valores reales; el mecanismo, en `StagedBackoffIT` con tiempos
  comprimidos. Ninguna de las dos pruebas basta sola.
- La prueba obligatoria de matar el consumidor a media tarea queda en F2, que es
  cuando existe un worker que matar. Ahí se verificará que `x-delivery-limit` sí
  corta cuando se pierde la conexión.
