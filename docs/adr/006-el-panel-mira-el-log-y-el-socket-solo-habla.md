# 006 — El panel mira el log, y el socket solo habla

- **Fecha:** 2026-09-05
- **Estado:** aceptado
- **Ámbito:** job-forge

## Contexto

El panel tiene que enseñar en vivo lo que le pasa a las tareas y ofrecer las
palancas para romper el sistema. Es **público y anónimo**, así que cada decisión
de diseño es también una decisión de seguridad: no hay usuario a quien
responsabilizar ni sesión que limitar por cuenta.

Hay dos preguntas que responder. De dónde saca los datos, y por dónde entran las
acciones.

## Decisión

**El panel es un consumidor más del log de auditoría.** No observa a los workers
ni consulta una base de datos: lee `trabajos.eventos`, que ya existe desde F3, y
lo reenvía por WebSocket. Enseña lo mismo haya una réplica o veinte, y lo que se
ve es exactamente lo que quedará registrado.

**El socket es de una sola dirección.** El servidor empuja; lo que llegue del
cliente por el socket se ignora. Las acciones —generar tareas, subir un archivo,
reprocesar la cola muerta— van por HTTP, que es donde se pueden limitar, medir y
auditar con las herramientas de siempre.

**Un grupo de consumidores por instancia del API,** con sufijo aleatorio. Es lo
contrario de lo que hace el proyector, y por una buena razón: el proyector
reparte trabajo y quiere un grupo compartido; el panel difunde, y con un grupo
compartido cada instancia vería solo un trozo de los eventos.

**La profundidad de las colas se pregunta al broker,** una vez por segundo y solo
si hay alguien mirando. No se puede derivar de los eventos: los mensajes que
esperan en los tramos de reintento los mueve el propio broker al vencer el TTL,
sin que ningún servicio se entere.

**Topes desde el principio:** máximo de sesiones simultáneas, cierre del cliente
lento en vez de acumularle mensajes, y un tope de eventos retenidos en el
navegador.

## Costo asumido

- **Cada visitante ocupa una conexión viva.** El tope de sesiones protege al
  servidor, pero significa que pasado el límite hay quien no puede entrar. Para un
  demo es aceptable; el número correcto sale de la memoria del pod, no del gusto.
- **La consulta de profundidad de colas es un sondeo, no un evento.** Una vez por
  segundo y por instancia del API. Con muchas réplicas eso es carga constante
  contra el broker aunque no pase nada. Se mitiga sondeando solo cuando hay
  sesiones abiertas, pero el patrón sigue siendo *polling* disfrazado.
- **La foto llega hasta un segundo tarde**, así que una ráfaga muy corta puede
  aparecer y desaparecer entre dos fotos y no verse nunca.
- **El panel hereda la semántica del log**: at-least-once. Un evento puede
  aparecer dos veces en el listado. Para mirar está bien; para contar, no.
- **El cliente lento se desconecta sin avisar al usuario** más allá del indicador
  de conexión. Se reconecta solo, con espera creciente, pero se habrá perdido lo
  que pasó mientras tanto: el panel no reproduce historia, solo enseña el presente.
- **`setAllowedOriginPatterns("*")`** está abierto porque en desarrollo el panel se
  sirve desde otro origen. En F7, detrás del ingress, tiene que acotarse al
  dominio real o cualquier página puede abrir este socket.

## Alternativas descartadas

- **Polling desde el navegador**, sin socket. Más simple y sin estado en el
  servidor. Descartada porque el demo trata de tiempo real: ver la cola de espera
  vaciarse sola es el argumento, y con refrescos cada dos segundos se pierde.
- **STOMP sobre SockJS**, que es lo que trae Spring de serie. Da suscripción por
  tema y reconexión hecha. Descartada por peso: obliga a meter dos librerías en el
  panel para resolver un problema que aquí no existe, porque solo hay un flujo y
  va en una dirección.
- **Aceptar acciones por el socket.** Menos código en el cliente y una sola vía.
  Descartada porque convertiría un canal público en superficie de escritura:
  habría que validar y limitar cada mensaje entrante, cuando HTTP ya trae todo eso
  resuelto y el ingress de F7 lo limitará sin saber nada de la aplicación.
- **Que el panel consulte directamente a RabbitMQ y a Kafka** desde el navegador.
  Descartada por lo obvio: expondría los brokers a internet.
- **Servir el panel desde el propio API** como recursos estáticos. Es lo que se
  hará al empaquetar en F5; durante el desarrollo se usa `ng serve` con proxy para
  no reconstruir el backend a cada cambio de CSS.

## Consecuencias

- El API gana una dependencia de consumo de Kafka, no solo de publicación.
- El panel se construye aparte (`apps/job-forge/ui`) y en desarrollo habla con el
  API por un proxy declarado en `proxy.conf.json`. Empaquetarlo para producción es
  trabajo de F5.
- `make run-ui` levanta el panel; el API por defecto escucha en el 8080, que en
  algunas máquinas está ocupado — se cambia con `SERVER_PORT`.
- El origen permitido del WebSocket queda como deuda explícita para F7.
