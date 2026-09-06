# 001 — Monorepo con Maven multimódulo, un agregador por demo

- **Fecha:** 2026-09-03
- **Estado:** aceptado
- **Ámbito:** monorepo

## Contexto

`job-forge` y `stream-guard` son dos productos independientes que comparten
infraestructura (brokers, cluster, pipeline, observabilidad) y convenciones, pero
no comparten dominio ni ciclo de vida: el segundo empieza cuando el primero está
desplegado punta a punta.

Dentro de cada demo sí hay código compartido de verdad: nombres de exchanges,
colas, topics y headers tienen que ser idénticos entre el productor, el worker y
el módulo de auditoría, o la topología se rompe en tiempo de ejecución con un
error que no aparece hasta que el mensaje ya se perdió.

El repositorio es público y su audiencia principal son revisores del ecosistema
Java, que esperan reconocer la estructura al primer vistazo.

## Decisión

Un solo repositorio. Cada demo es un agregador Maven independiente bajo `apps/`,
con módulos por unidad desplegable: `contracts`, `api`, `worker`, `audit`, `ui`.
El módulo `contracts` es la única fuente de verdad de nombres de colas, topics y
headers, y lo consumen los demás módulos de su demo.

No hay POM padre común a los dos demos. Comparten infraestructura y convenciones,
no dependencias.

## Costo asumido

- **Un cambio en `contracts` recompila y redespliega todo su demo.** Es
  deliberado: si cambia el nombre de una cola, quiero que se entere todo el que la
  usa, no descubrirlo en producción.
- **CI reconstruye de más.** Sin filtros por ruta, un cambio en el README dispara
  el build completo. Se resuelve en F6 con `paths` en los workflows; hasta
  entonces se paga en minutos de GitHub Actions.
- **Maven es más verboso y más lento que Gradle**, y el build incremental es peor.
  A esta escala (dos demos, nueve módulos) la diferencia es de segundos.
- **Sin BOM común**, las versiones de Spring Boot pueden divergir entre los dos
  demos. Aceptable mientras `stream-guard` no exista; hay que vigilarlo desde F8.

## Alternativas descartadas

- **Un repositorio por demo.** Duplicaría Terraform, workflows y dashboards, que
  es justamente lo que ambos demos comparten. Además destruye el argumento
  central del proyecto: el valor está en verlos lado a lado, con la misma capa de
  operación, defendiendo tesis opuestas.
- **Gradle con version catalog.** Mejor herramienta en lo técnico —build
  incremental, configuración más legible en Kotlin DSL—, pero el repositorio es
  material de entrevista para el ecosistema Spring, donde Maven es el terreno
  conocido. Menos superficie donde algo raro pueda fallar frente a un revisor.
- **Un único agregador para los dos demos.** Acopla ciclos de vida que no tienen
  por qué estar acoplados y hace que un cambio en `stream-guard` toque el build de
  `job-forge`.

## Consecuencias

- Los directorios se crean en la fase que los estrena. `apps/stream-guard/` no
  existe hasta F8.
- El esqueleto Maven de `job-forge` nace en F1, junto con la topología de
  RabbitMQ que es su primer contenido real.
- Ningún módulo declara nombres de colas o topics como literales propios: los
  toma de `contracts`.
