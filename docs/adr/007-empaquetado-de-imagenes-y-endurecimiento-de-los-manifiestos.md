# 007 — Empaquetado de imágenes y endurecimiento de los manifiestos

- **Fecha:** 2026-09-06
- **Estado:** aceptado
- **Ámbito:** infraestructura

## Contexto

F5 convierte cuatro procesos que se ejecutaban con `mvn spring-boot:run` y
`ng serve` en cuatro imágenes desplegadas en un cluster. El código no cambia de
propósito, pero sí cambian todas las suposiciones que hacía sobre su entorno:
quién es el usuario, qué puede escribir, cuánta memoria tiene, cómo se le avisa
de que va a morir y quién decide si está sano.

Casi todas las decisiones de aquí abajo salieron de intentar algo y verlo fallar,
no de elegir sobre el papel. Se documentan con el fallo incluido porque el fallo
es la mitad del argumento.

## Decisión

### 1. Imagen base `eclipse-temurin:21-jre-alpine`, no distroless

Alpine con JRE de Temurin para los tres servicios Java, `nginx-unprivileged`
alpine para el panel. Build multi-stage, usuario no root con uid numérico.

El criterio fue didáctico antes que métrico: este repositorio existe para que
alguien pueda abrirlo y entender qué pasa dentro. Distroless no trae shell, así
que `kubectl exec` deja de servir justo en el momento en que más falta hace.
Imagen resultante: 257 MB los de Java, 54 MB el panel.

### 2. El JRE de Temurin no trae el módulo `jdk.random`

Esto no se eligió: se descubrió, y obligó a cambiar código de F2.

`FailureInjector` y `TaskProcessor` usaban `RandomGenerator.getDefault()`. El
algoritmo que ese método devuelve —`L32X64MixRandom`— vive en el módulo
`jdk.random`, y el JRE de Temurin se construye con 49 módulos entre los que no
está. Resultado: la aplicación arrancaba perfectamente en la máquina de
desarrollo, donde hay un JDK completo, y moría en la imagen con un
`IllegalArgumentException` que no menciona ni módulos ni empaquetado.

Los dos sitios pasan a usar `ThreadLocalRandom.current()`, que vive en
`java.base` y por tanto está siempre. De regalo, arregla un defecto latente que
no tenía nada que ver: el generador por defecto **no es seguro entre hilos**, y
la concurrencia del worker es una palanca del panel. Hoy vale 1; el día que se
suba, el fallo habría sido intermitente y muy caro de encontrar.

### 3. Un Dockerfile parametrizado, no tres

`apps/job-forge/Dockerfile` con `--build-arg MODULE=worker|api|audit`. El reactor
de Maven es uno solo y los tres servicios se construyen igual.

### 4. La cadena de plazos del apagado, estrictamente creciente

Para el worker, de dentro afuera: `shutdown-timeout` del contenedor de Rabbit
(30 s) < `timeout-per-shutdown-phase` de Spring (45 s) <
`terminationGracePeriodSeconds` (60 s). Si el orden se invierte, gana el plazo
más corto y todo lo demás es decorativo.

El `preStop` **solo** lo llevan `api` y `ui`. La ventana entre el `SIGTERM` y la
salida del pod de los endpoints del Service afecta a quien recibe tráfico; el
worker va él a buscar el trabajo a la cola, así que esa ventana no existe para
él. Poner un `preStop` en un consumidor es retrasar su apagado sin motivo.

### 5. La readiness de cada servicio dice algo distinto

- `api`: el contexto está levantado. Es un servicio HTTP; eso es la verdad.
- `worker`: terminó de reconstruir su estado desde el topic compactado
  (`stateRebuildRunner`, ADR 005). Ya existía desde F3.
- `audit`: **tiene particiones asignadas**. Se añade en F5
  (`KafkaConsumerAssignmentHealthIndicator`); antes solo tenía `probes.enabled`,
  que en un consumidor de Kafka no distingue "arrancado" de "consumiendo".
- `ui`: nginx responde. No consulta al backend a propósito: este pod sirve HTML y
  lo sirve bien aunque RabbitMQ esté caído. Atarlo a la salud del broker haría
  que una caída se llevara por delante hasta la pantalla que la explica.

Ninguna `livenessProbe` mira un broker. Ese es el error que convierte una caída
pasajera de RabbitMQ en un `CrashLoopBackOff` simultáneo de todas las réplicas.

### 6. Los brokers se escriben a mano

`k8s/charts/platform` declara RabbitMQ y Kafka como StatefulSets propios, un nodo
cada uno, en vez de traer charts de terceros.

### 7. `readOnlyRootFilesystem` en la aplicación, no en los brokers

Los cuatro servicios de `job-forge` corren con la raíz en solo lectura y un
`emptyDir` en `/tmp` —verificado con `docker run --read-only`, que es lo que
destapó que la JVM y nginx necesitan ese `/tmp`—. RabbitMQ y Kafka no: el runtime
de Erlang y la imagen de Kafka escriben fuera del directorio de datos, y mapear
cada ruta a un volumen produce un manifiesto más frágil que seguro.

La excepción queda escrita en el propio manifiesto, no escondida.

## Costo asumido

- **Alpine usa musl, no glibc.** Para código Java puro da igual, pero cualquier
  dependencia con binarios nativos —compresión, criptografía acelerada— puede
  comportarse distinto o directamente no cargar. Hoy no hay ninguna; el día que
  la haya, el síntoma será un `UnsatisfiedLinkError` y este párrafo es la pista.
- **Se conserva un shell en la imagen de producción.** Es superficie de ataque
  real, aceptada a cambio de poder depurar. Si el repositorio dejara de ser
  didáctico, esta decisión debería revisarse la primera.
- **Cambiar `RandomGenerator` por `ThreadLocalRandom` reduce la calidad
  estadística** del generador. Para decidir si una tarea simulada falla, es
  irrelevante. Para criptografía sería inaceptable, y no se usa para eso.
- **`FailureInjectorTest` sigue pidiendo `RandomGenerator.of("L64X128MixRandom")`,**
  que solo existe si las pruebas corren sobre un JDK completo. Hoy es cierto —
  Maven usa el JDK—, pero es la misma mina en otro sitio. Se deja anotado en vez
  de arreglado en silencio.
- **El Dockerfile parametrizado obliga a recordar el `--build-arg`.** Un
  `docker build` sin él falla con un mensaje de Maven poco evidente. El Makefile
  lo encapsula, pero quien construya a mano se lo va a encontrar.
- **`MODULE` es el directorio, no el artifactId.** `-pl` de Maven resuelve rutas.
  Pasarle `job-forge-worker` en vez de `worker` da un
  `Could not find the selected project in the reactor` que no explica nada. Costó
  un ciclo de build descubrirlo.
- **Los brokers escritos a mano no tienen alta disponibilidad ni la fingen.** Un
  nodo de cada uno, sin quórum de controllers, sin réplicas de colas. Para lo que
  el demo enseña sobra; como referencia de producción, no vale.
- **Las NetworkPolicy no se verifican en local.** El CNI por defecto de kind no
  las aplica: se crean y no filtran nada. La comprobación real es en EKS (F6). No
  confundir "el manifiesto está aplicado" con "la regla está en vigor".
- **Se ponen `limits` de CPU porque `CLAUDE.md` los exige, y no estoy de acuerdo.**
  La CPU es un recurso comprimible: pasarse del límite no mata el proceso, lo
  estrangula, y en una JVM eso se manifiesta como pausas de GC y latencia
  errática que nadie relaciona con el manifiesto. Lo habitual en Java es fijar
  `requests` de CPU y omitir `limits`. Se cumple la regla escrita y se deja aquí
  la objeción para que la decisión sea explícita y no un descuido.
- **El tope de tres réplicas de `audit` es real y lo impone el chart.** Con la
  readiness atada a la asignación de particiones, una cuarta réplica nunca se
  declararía lista y bloquearía el despliegue. El chart falla al renderizar con
  un mensaje que lo explica, en vez de dejar que se descubra en el cluster.

## Alternativas descartadas

- **Distroless.** Unos 120 MB menos y menos superficie. Se descarta por lo dicho
  en la decisión 1: sin shell, `kubectl exec` no sirve, y este repositorio se lee
  tanto como se ejecuta.
- **`eclipse-temurin:21-jdk-alpine`** para esquivar lo de `jdk.random` sin tocar
  código. Se descarta porque mete un compilador en producción para no cambiar dos
  líneas, y porque el cambio a `ThreadLocalRandom` arregla además la seguridad
  entre hilos.
- **Jar por capas de Spring Boot** (`jarmode=tools extract --layers`), que mejora
  el aprovechamiento de la caché de Docker separando dependencias de código
  propio. Se descarta en F5 por no introducir un concepto más en una fase que ya
  trae siete. Es la primera mejora si los builds molestan.
- **Tres Dockerfiles.** Más obvios de leer uno a uno. Se descartan porque la
  próxima corrección de seguridad se aplicaría en dos de los tres.
- **Charts de terceros para los brokers** (Bitnami y equivalentes). Mantenidos y
  con alta disponibilidad de verdad. Se descartan porque traen cientos de valores
  para escenarios que este repositorio no tiene y esconden exactamente la
  configuración que el repositorio quiere enseñar; y porque añaden una dependencia
  externa al stack fijo.
- **Servir el panel desde el propio Spring Boot**, como recursos estáticos. Evita
  una imagen y evita a nginx del todo. Se descarta porque acopla el despliegue del
  panel al del API: un cambio de CSS obligaría a reiniciar el servicio que sostiene
  los WebSocket.
- **Que nginx del panel proxifique `/api` y `/ws`** en vez de enrutar el Ingress.
  Habría evitado necesitar un controlador de Ingress en kind. Se descarta porque
  dejaría dos sitios donde se decide el enrutado, y F7 tiene que poner el rate
  limiting en uno solo.

## Consecuencias

- `k8s/charts/` y `k8s/overlays/local/` nacen aquí. `k8s/overlays/prod/` y
  `k8s/argocd/` siguen sin existir: nacen en F6, cuando haya registro y pipeline.
- El `image.tag` por defecto está vacío y el chart **falla al renderizar** si el
  values del entorno no lo trae. Verificado.
- `audit.replicas` mayor que 3 hace fallar el renderizado con un mensaje que
  explica el porqué. Verificado.
- La pregunta abierta que dejó el ADR 004 —si perder la conexión agota
  `x-delivery-limit`— se responde en la verificación de esta misma fase, matando
  pods de verdad.
