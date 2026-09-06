# 004 — Publicar, confirmar y solo entonces acusar

- **Fecha:** 2026-09-04
- **Estado:** aceptado
- **Ámbito:** job-forge

## Contexto

El ADR 003 fijó que el worker republica en lugar de reencolar. Al implementarlo
aparece la pregunta que ese ADR dejaba implícita: **en qué orden exacto** hace el
worker las tres operaciones que tiene entre manos —terminar la tarea, poner el
reintento a salvo y soltar el mensaje original— y qué pasa si se muere en cada
uno de los huecos entre ellas.

Publicar en AMQP es asíncrono. `send()` vuelve enseguida y eso no significa que
el mensaje exista en ninguna parte. Sin confirmaciones del publicador, el worker
publica a ciegas.

## Decisión

**El orden es: publicar el reintento, esperar la confirmación del broker, y solo
entonces acusar el mensaje original.** Si la confirmación no llega, no se acusa:
el trabajo sigue siendo del broker. El mismo orden gobierna el reproceso desde la
cola muerta en el API.

De ahí se derivan cuatro decisiones más:

- **Acuse manual** en el consumidor. Con acuse automático el broker da por
  entregada la tarea al escribirla en el socket, y un worker que muera procesando
  se la lleva consigo sin que nadie se entere.
- **Prefetch 1 por defecto.** Las tareas de `job-forge` son largas y desiguales, y
  un prefetch alto hace que un worker acapare trabajo que sus compañeros ociosos
  ya no pueden tomar. Además rompe las prioridades: lo que ya está en el buffer
  local del worker tiene el orden decidido.
- **La probabilidad de fallo viaja en el header `x-fail-probability`** de cada
  tarea, no en la configuración del worker.
- **Deduplicación por `x-task-id`** en un registro en memoria con caducidad y tope
  de tamaño, consultado *antes* de trabajar.

## Costo asumido

- **La espera de confirmación bloquea un hilo del worker.** Con el broker sano son
  milisegundos; con el broker enfermo son segundos por tarea, y el pool se
  degrada. El plazo es configurable, y elegirlo es elegir entre falsos negativos
  —dar por perdida una publicación que sí llegó, y duplicar— y hilos parados.
- **El hueco entre confirmar y acusar sigue existiendo.** Si el worker muere ahí,
  la tarea está publicada en el tramo de reintento *y* volverá a entregarse desde
  la cola de trabajo: se procesará dos veces. Es deliberado: preferimos duplicar a
  perder, y por eso la idempotencia no es opcional.
- **Prefetch 1 cuesta throughput.** Cada tarea paga un viaje de ida y vuelta. Es el
  precio del reparto justo, y es subible desde la configuración cuando el perfil
  de las tareas lo justifique.
- **La palanca en el header no alcanza a las tareas ya encoladas.** Cambiar la tasa
  de fallo afecta a lo que se publique después, no a lo que espera en la cola.
  Para el panel es entendible; para un sistema de control de verdad, no lo sería.
- **El registro de deduplicación vive en la memoria del proceso.** No se comparte
  entre réplicas y muere con el pod. Solo protege frente a duplicados que caigan en
  la misma réplica antes de que caduque la ventana. Está probado explícitamente,
  incluida su caducidad, para que la limitación esté escrita y no se descubra en
  producción. F3 lo resuelve reconstruyendo el estado desde el topic compactado.
- **Los archivos subidos no se almacenan**, así que no hay reproceso posible de una
  subida: si el trabajo se pierde entero, hay que volver a subir el archivo.

## Alternativas descartadas

- **Acusar primero y publicar después.** Más simple y sin ventana de duplicados.
  Descartada porque su modo de fallo es la pérdida silenciosa: la tarea ya no está
  en la cola de trabajo y nunca llegó a la de espera. Duplicar es recuperable;
  perder no.
- **Transacciones AMQP** en lugar de confirmaciones. Dan atomicidad real entre
  publicar y acusar. Descartadas por coste: una transacción por mensaje hunde el
  throughput de RabbitMQ, y el problema que resuelven —una ventana de duplicados
  que la idempotencia ya cubre— no lo justifica.
- **Endpoint REST en el worker para la tasa de fallo.** Lo más directo con una sola
  réplica. Descartada porque con N réplicas detrás de un Service la petición
  alcanza a una sola y el demo empieza a mentir justo cuando se escala, que es lo
  que queremos enseñar.
- **Exchange fanout de control** para la misma palanca. Alcanza a todas las
  réplicas al instante, incluidas las tareas ya encoladas, y enseña fanout y colas
  exclusivas. Descartada por ahora: más topología a cambio de un matiz que el
  header ya cubre, y un worker que arranque después no conocería el último valor.
- **Deduplicación en Redis.** Compartida entre réplicas y sobrevive a los
  reinicios. Descartada porque añade un servicio fuera del stack para resolver algo
  que F3 va a resolver con Kafka, que ya está en el stack.

## Consecuencias

- `ConfirmedPublisher` vive en `contracts` y lo usan el worker y el API: el orden
  correcto se implementa una vez.
- Los mensajes no llevan el nombre de la clase Java. El convertidor fija el tipo en
  el código, lo que evita que un header perdido al republicar rompa el segundo
  intento y deja el formato libre para que Kafka lo lea en F3.
- Ambos servicios publican con `mandatory`, de modo que una routing key equivocada
  produce un error visible en vez de un descarte silencioso.
- El apagado ordenado está configurado, pero **sin verificar**: se comprobará en F5
  con el pod real y su periodo de gracia.

## Pregunta abierta

Queda sin resolver si perder la conexión agota `x-delivery-limit`. Lo medido:

- Reencolar con un `nack` **no** lo agota. El broker incrementa
  `x-acquired-count`; el mensaje sobrevivió a más de 11 000 reencolados (ADR 003).
- Cortando la conexión desde el broker con `rabbitmqctl`, el mensaje sin acusar
  **sí vuelve a la cola** y se reentrega. Eso está verificado en
  `WorkerRedeliveryIT`.
- No se consiguió reproducir de forma fiable una secuencia de varias caídas sobre
  la misma tarea, así que **no se puede afirmar** que el límite acabe apartándola.

El argumento se mantiene declarado porque no cuesta nada y el comportamiento
esperado es el correcto, pero hasta verificarlo no debe presentarse como una
defensa real. Se comprobará en F5, matando pods de verdad, que es donde el caso
ocurre.
