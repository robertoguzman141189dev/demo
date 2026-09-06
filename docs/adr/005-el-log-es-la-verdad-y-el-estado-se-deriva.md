# 005 — El log es la verdad; el estado se deriva de él

- **Fecha:** 2026-09-04
- **Estado:** aceptado
- **Ámbito:** job-forge

## Contexto

F2 dejó una limitación escrita y probada: el registro de tareas completadas vive
en la memoria del worker, no se comparte entre réplicas y muere con el pod. Cada
reinicio significaba volver a ejecutar trabajo ya hecho, y la idempotencia solo
funcionaba dentro de la vida de un proceso.

La solución obvia sería una base de datos. Aquí no la hay, y no por capricho: el
demo existe para demostrar que un topic compactado de Kafka puede sostener estado
consultable sin persistencia propia.

Aparece además un problema que hasta ahora no existía. El worker acusa el mensaje
en RabbitMQ **y** escribe el evento en Kafka: dos sistemas, sin forma de hacer
ambas cosas atómicamente sin transacciones distribuidas. Es la escritura doble, y
no se puede eliminar — solo se puede elegir de qué lado fallar.

## Decisión

**Dos topics con papeles distintos.** `trabajos.eventos` (política `delete`, con
retención por tiempo) guarda la historia: un hecho por cada cosa que le ocurre a
una tarea. `trabajos.estado` (política `compact`) guarda el estado actual de cada
tarea, y se **deriva** del anterior.

**Los emisores no proyectan.** El API y el worker publican hechos; un servicio
aparte, `audit`, los lee y escribe el estado. La proyección vive en un solo sitio
y el estado se puede tirar entero y recalcular releyendo el log.

**Ambos topics van con el id de la tarea como clave.** De ahí sale todo lo demás:
Kafka reparte por hash de la clave y solo garantiza el orden dentro de una
partición, así que toda la historia de una tarea vive junta y en orden. Y la
compactación necesita clave para poder funcionar.

**En la escritura doble, primero el hecho.** Se publica en Kafka, se espera a que
lo acepte, y solo entonces se acusa en RabbitMQ. Mismo criterio que el ADR 004.

**El worker reconstruye antes de trabajar.** Al arrancar lee `trabajos.estado`
entero, recupera qué tareas están completadas y solo entonces arranca sus
consumidores de RabbitMQ. Hasta ese momento la readiness está en rojo. Si no lo
consigue en el plazo, el proceso falla y el pod se reinicia.

El productor escribe con `acks=all` e idempotencia activada. Sin transacciones,
como pedía el diseño.

## Costo asumido

- **Un evento puede duplicarse.** Si el proceso muere entre publicar en Kafka y
  acusar en RabbitMQ, la tarea se reintentará y volverá a emitir su hecho. La
  proyección es idempotente por clave —el último valor gana— así que no hace daño,
  pero el log de eventos **no** es una secuencia exacta de lo ocurrido: es un
  registro at-least-once. Contar eventos para sacar estadísticas exactas daría
  números inflados.
- **El estado va por detrás de la realidad.** Entre que el worker completa una
  tarea y el proyector escribe su estado pasan milisegundos, a veces más. Un worker
  que arranque en esa ventana no verá la tarea como completada y podría repetirla.
  Se ha reducido el agujero, no cerrado.
- **La compactación es eventual.** El segmento activo nunca se compacta, así que al
  leer aparecen varias versiones de la misma clave y hay que quedarse con la
  última. El topic también ocupa más de lo que "debería" durante un tiempo. Está
  cubierto por una prueba que siembra dos versiones a propósito.
- **El arranque del worker es más lento y depende de Kafka.** Si Kafka no responde,
  el worker no arranca. Es deliberado —preferimos un pod que se reinicia a uno que
  repite trabajo— pero convierte a Kafka en dependencia de arranque de un servicio
  que, en teoría, solo necesitaba RabbitMQ.
- **El estado por tarea crece sin límite.** Compactar no es caducar: conserva la
  última versión de cada clave para siempre. Con tareas efímeras y sin borrarlas
  con tombstones, el topic crece indefinidamente. En un demo público con topes es
  asumible; en producción exigiría una política de borrado.
- **Un servicio más** que desplegar, vigilar y explicar.
- **Reparticionar `trabajos.estado` rompería el estado hacia atrás**, porque cambia
  el hash de las claves. Ese topic no se toca sin migración.

## Alternativas descartadas

- **Una base de datos** para el registro de tareas completadas. Es lo correcto en
  un sistema real y lo primero que haría cualquiera. Descartada porque añade un
  servicio fuera del stack y desmonta justo lo que el demo quiere enseñar: que un
  topic compactado sostiene estado consultable.
- **Que cada emisor escriba también el estado**, sin proyector. Menos piezas.
  Descartada porque duplica la lógica de proyección en cada servicio y hace que el
  estado dependa de que todos la implementen igual; además desaparece la
  demostración de que el estado se deriva del log.
- **Solo el topic compactado, sin log de eventos.** Lo más simple. Descartada
  porque pierde la historia —qué pasó y cuándo—, que es justamente lo que hace útil
  una auditoría.
- **Transacciones de Kafka** para atar la escritura del evento con la del estado.
  Excluidas por el diseño desde el principio, y además no resolverían el problema
  real, que es la atomicidad entre RabbitMQ y Kafka: eso ninguna transacción de
  Kafka lo cubre.
- **Reconstruir en segundo plano y empezar a consumir de inmediato.** Arranque más
  rápido y tolerante a que Kafka esté caído. Descartada porque durante la ventana
  de reconstrucción el worker no reconoce duplicados, y entonces cada despliegue
  produce exactamente la tanda de trabajo repetido que F3 venía a evitar.
- **Kafka Streams** para la proyección. Es el stack de `stream-guard` y aquí sería
  desproporcionado: la proyección no necesita estado propio ni ventanas, porque la
  clave garantiza el orden y cada evento sabe convertirse en el estado que deja.

## Consecuencias

- Los topics se declaran en código, en `contracts`, igual que la topología de
  RabbitMQ. Nada de autocreación: un topic autocreado tiene una partición y la
  retención por defecto, que es lo contrario de lo que necesita cada uno.
- La readiness del worker deja de ser "el puerto responde" y pasa a ser "ya sé qué
  tareas están hechas y estoy consumiendo". Los consumidores de RabbitMQ arrancan
  parados a propósito.
- El worker lee el topic con `assign` y sin grupo de consumidores: no reparte
  trabajo con nadie —cada réplica necesita todo el estado— ni debe recordar por
  dónde iba, porque la próxima vez tiene que volver a leerlo entero.
- El formato en el cable sigue sin llevar nombres de clases Java, como en AMQP.
