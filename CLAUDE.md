# event-lab

Monorepo con dos demos de mensajería distribuida que comparten infraestructura,
pipeline y observabilidad. Este archivo es el contexto permanente del proyecto:
léelo completo antes de proponer o escribir nada.

---

## 1. Cómo trabajar conmigo

Soy arquitecto de software con 9 años en Java/Spring Boot, .NET, Angular y
Flutter. Sé programar. Lo que estoy aprendiendo aquí es **mensajería distribuida
y operación en Kubernetes**. El repo es público y lo va a leer gente que decide
contrataciones.

**Regla que gobierna todo lo demás:** antes de escribir código que use un
concepto que no hayamos tocado todavía (state store, prefetch, rebalance, DLX,
KEDA, IRSA, probes, apagado ordenado, lo que sea), párate y explícamelo en 5 a 10
líneas: qué es, qué problema resuelve, qué pasa si se configura mal. Espera mi
confirmación explícita antes de continuar. Prefiero avanzar lento y entender que
tener un repo que no puedo defender en una entrevista.

Corolarios:

- **No avances de fase sin que yo lo pida.** Termina la fase actual y detente.
- Si detectas una contradicción en lo que pedí, dímelo antes de resolverla por tu
  cuenta.
- Ningún servicio, librería o herramienta fuera del stack fijo sin proponérmelo
  primero con su justificación y su costo.

---

## 2. Los dos demos y sus tesis

Existen para demostrar que sé **elegir el broker correcto**, no que tengo un
favorito. Cada uno defiende una tesis distinta.

### Demo 1 — `job-forge` (RabbitMQ protagonista)

Orquestador de trabajos. El visitante sube un archivo, se descompone en tareas y
un pool de workers las procesa. El visitante puede **inyectar fallos en vivo** y
ver el sistema absorberlos.

**Tesis:** la resiliencia en la entrega es un problema distinto al del
throughput. RabbitMQ está aquí por acuse de recibo por unidad de trabajo, TTL,
prioridades y dead-letter routing — cosas que un log distribuido no da.

Topología obligatoria:

- Exchange `jobs` (direct) → cola `jobs.work` (quorum, `x-message-ttl`,
  `x-delivery-limit`, DLX hacia `jobs.dlx`).
- Colas de espera escalonadas `jobs.retry.5s`, `jobs.retry.30s`, `jobs.retry.2m`,
  cada una con su TTL y su DLX de vuelta a `jobs`. Eso es backoff exponencial sin
  scheduler externo.
- Cola `jobs.dead` con inspección y reproceso manual desde el panel.
- Contador de intentos en el header `x-attempt`, **nunca** en el cuerpo del
  mensaje.

Tres correcciones al diseño original, medidas contra RabbitMQ 4.3.5 (ADR 003):

- **`x-max-priority` no existe en colas quorum**; el broker rechaza la
  declaración. La prioridad va en la propiedad `priority` del mensaje, con dos
  niveles: normal (0) y alta (5).
- **El worker republica, nunca reencola.** El dead-lettering no deja elegir
  destino ni modificar headers, así que el escalonamiento lo decide el consumidor:
  publicar al tramo que toca, confirmar, y solo entonces acusar el original.
- **Reencolar no agota `x-delivery-limit`**: un `nack` con reencolado incrementa
  `x-acquired-count`, no el contador de entregas. Ese límite cubre al worker que
  muere y pierde la conexión, no a los fallos de negocio.
- Kafka aparece solo como log de auditoría: `trabajos.eventos` (delete) y
  `trabajos.estado` (compact, para reconstruir estado sin base de datos).

Palancas del panel: tasa de fallo del worker (0–100 %), número de réplicas,
prefetch, matar un worker a media tarea, reprocesar desde la DLQ.

### Demo 2 — `stream-guard` (Kafka protagonista)

Detección de fraude en tiempo real sobre un flujo de transacciones sintéticas.

**Tesis:** mantener el estado del comportamiento reciente *dentro del consumer*,
particionado por cliente y en un state store local, es lo que permite decidir en
milisegundos sin consultar base de datos. Kafka está aquí por particionamiento
con garantía de orden, estado colocalizado y replay — no por volumen.

Topics: `transacciones` (clave `clienteId`, 12 particiones, delete),
`alertas` (clave `clienteId`, delete), `decisiones` (clave `alertaId`, compact).

Detectores, en este orden y **solo uno a la vez**:

1. Velocidad — N operaciones del mismo cliente en ventana deslizante de 10 min de
   *event time*, con grace period para eventos tardíos.
2. Salto geográfico — velocidad implícita contra la última ubicación en el state
   store.
3. Monto atípico — media y desviación móviles por cliente; resuelve
   explícitamente el arranque en frío.

RabbitMQ aparece solo para las órdenes de envío de notificación push.

Palancas del panel: generar transacción normal, sospechosa o ráfaga; matar un pod
del motor y ver el rebalance; escalar réplicas; rebobinar offset y reprocesar.

---

## 3. Stack fijo

No propongas alternativas sin preguntarme.

| Capa | Tecnología |
|---|---|
| Lenguaje y framework | Java 21, Spring Boot 3 |
| Mensajería | Apache Kafka 4.x en KRaft (sin ZooKeeper), RabbitMQ 4.x con colas quorum |
| Librerías de mensajería | Kafka Streams, Spring AMQP |
| Frontend | Angular, WebSocket para el tiempo real |
| Build | **Maven multimódulo** |
| Orquestación | Kubernetes, Helm, Argo CD, KEDA |
| Infraestructura | Terraform sobre AWS |
| CI | GitHub Actions |
| Observabilidad | Prometheus, Grafana, exporters de cada broker |

---

## 4. Infraestructura y despliegue continuo

**Cluster:** uno solo, con namespaces `job-forge`, `stream-guard`, `platform`
(brokers) y `observability`. Cada demo es una `Application` de Argo CD separada.

**Dos módulos de Terraform, la misma capa de Kubernetes encima:**

- `terraform/aws-eks/` — EKS con VPC propia, managed node group, IRSA, ECR. Es lo
  que quiero aprender a operar.
- `terraform/aws-ec2-k3s/` — una sola EC2 con k3s, para dejar el demo público
  encendido sin quemar presupuesto.

El control plane de EKS se cobra por hora esté o no en uso: verifica el precio
vigente antes de aplicar y configura un `aws_budgets_budget` con alerta desde el
día uno. Documenta en un ADR por qué el demo público corre donde corre.

**Flujo de CD — GitOps, nunca `kubectl apply` desde el pipeline:**

1. Push a `main` → GitHub Actions corre tests y construye imagen multi-stage.
2. Tag inmutable con el SHA del commit. Nunca `:latest`.
3. Push a ECR con autenticación OIDC, sin llaves de acceso guardadas en GitHub.
4. El pipeline actualiza el tag en `k8s/overlays/prod/`.
5. Argo CD detecta el cambio y sincroniza. **El pipeline nunca toca el cluster.**

---

## 5. Buenas prácticas obligatorias en los manifiestos

Cada Deployment que generes debe traer esto sin que yo lo pida:

- `readinessProbe`, `livenessProbe` y `startupProbe` diferenciadas. La readiness
  de un consumidor no es "el puerto responde", es "estoy asignado y consumiendo".
- `resources.requests` y `resources.limits` en todos los contenedores.
- `securityContext` con `runAsNonRoot`, `readOnlyRootFilesystem`,
  `allowPrivilegeEscalation: false` y `capabilities.drop: [ALL]`.
- `terminationGracePeriodSeconds` generoso y apagado ordenado: un consumidor de
  Kafka debe comitear su offset y salir del grupo limpiamente; uno de RabbitMQ
  debe terminar el mensaje en curso y acusarlo.
- `PodDisruptionBudget` para brokers y consumidores.
- `topologySpreadConstraints` en el módulo de EKS con más de un nodo.
- `NetworkPolicy`: los namespaces de aplicación solo hablan con `platform`.
- `ServiceAccount` dedicado por servicio, con IRSA en EKS. Nunca el default.
- Secretos vía External Secrets contra AWS Secrets Manager. Nada de secretos en
  el repo, ni siquiera cifrados.
- Imagen base distroless o `-alpine`, build multi-stage, usuario no root.

**Autoescalado con KEDA, no con HPA de CPU.** `job-forge` escala por profundidad
de `jobs.work`; `stream-guard` por consumer lag del grupo. En Kafka no sirve
escalar más allá del número de particiones.

---

## 6. Restricciones duras

- Los paneles son **públicos y anónimos**. Rate limiting en el ingress, tope de
  operaciones por sesión y tope global. El endpoint no puede ser un vector de
  abuso ni de gasto.
- Todos los datos son sintéticos. Los archivos subidos se borran a los 30 minutos
  y la interfaz lo dice.
- **Idempotencia obligatoria en ambos lados.** At-least-once es la semántica por
  defecto y los consumidores deben tolerar duplicados. No uses transacciones de
  Kafka.
- Sin autenticación de usuarios.

---

## 7. Convenciones

- **Código y nombres en inglés. Comentarios, documentación y ADRs en español.**
- Tests con Testcontainers para todo lo que toque un broker. La topología de
  Streams se prueba con `TopologyTestDriver`, sin broker.
- Dos tests obligatorios en `job-forge`: matar el consumidor a media tarea y
  verificar reentrega; y verificar que el backoff escalonado respeta los tiempos.
- Cada decisión no obvia va en `docs/adr/NNN-titulo.md` con contexto, decisión,
  **costo asumido** y alternativas descartadas. Un ADR sin trade-off explícito no
  vale nada.
- Conventional commits.

---

## 8. Qué NO hacer

- No uses Kafka como cola de trabajo ni RabbitMQ como log. Los dos demos existen
  precisamente para desmentir esos dos errores.
- No hagas sofisticado el procesamiento de `job-forge`. El trabajo es un
  pretexto; la mecánica de entrega es el producto.
- No escribas el segundo detector de `stream-guard` hasta que el primero esté
  desplegado punta a punta.
- No abras el README con una lista de tecnologías. Abre con el problema.
- No crees directorios ni archivos "para más adelante". Cada fase crea lo suyo.

---

## 9. Plan de fases

Trabajamos `job-forge` completo primero — es más corto y el demo es más vistoso —
y `stream-guard` después, reutilizando toda la infraestructura.

| Fase | Contenido | Estado |
|---|---|---|
| F0 | `CLAUDE.md`, estructura, `docker-compose.yml` local, `make demo` | hecho |
| F1 | Topología de RabbitMQ en código, reintentos escalonados y DLQ | hecho |
| F2 | Productor de tareas y worker con fallo inyectable | hecho |
| F3 | Auditoría en Kafka y reconstrucción de estado desde el compactado | hecho |
| F4 | Panel Angular con palancas y WebSocket | hecho |
| F5 | Dockerfiles, Helm charts, despliegue en kind | hecho |
| F6 | Terraform (EKS y EC2/k3s), Argo CD, GitHub Actions con OIDC | casi: falta aplicar EKS |
| F7 | KEDA, dashboards de Grafana, blindaje del endpoint público | pendiente |
| F8+ | `stream-guard`, mismas fases sobre la infra ya montada | pendiente |

---

## 10. Decisiones ya tomadas

Están documentadas en `docs/adr/`. Resumen:

- **ADR 001** — Monorepo con Maven multimódulo, un agregador por demo.
- **ADR 002** — Helm es la unidad de empaquetado; `k8s/overlays/<env>/` contiene
  solo los `values` por entorno y es el único punto que el pipeline muta.
- **ADR 003** — El reintento escalonado se republica, no se reencola. Incluye las
  tres correcciones de la topología y por qué se descartó el plugin de mensajes
  retrasados.
- **ADR 006** — El panel mira el log, y el socket solo habla. Canal de una
  dirección, un grupo de consumidores por instancia para difundir, y topes de
  sesiones en un endpoint público.
- **ADR 005** — El log es la verdad; el estado se deriva de él. Dos topics, un
  proyector aparte, y el worker reconstruye desde el compactado antes de consumir.
  Cierra la limitación del registro en memoria que dejó F2.
- **ADR 004** — Publicar, confirmar y solo entonces acusar. Acuse manual, prefetch
  1, la palanca de fallo en el header y la deduplicación en memoria con su
  limitación escrita. Su pregunta abierta quedó **resuelta en F5**: sí, perder la
  conexión agota `x-delivery-limit`. Con el límite en 5, el mensaje sobrevivió a
  cinco muertes del pod y en la sexta entrega acabó en `jobs.dead`.
- **ADR 007** — Empaquetado y endurecimiento. Base alpine con shell a cambio de
  poder depurar, un Dockerfile parametrizado para los tres servicios Java, la
  cadena de plazos del apagado, y los tres fallos que solo aparecieron
  desplegando: sondas caras que se pelean con el arranque, un `startupProbe`
  corto que convierte un arranque lento en imposible, y que esperar a que un
  Deployment esté `Available` no es esperar a que sirva.
- **ADR 008** — El demo público corre en k3s sobre una EC2 y EKS se alquila por
  horas. Con precios consultados: 30 USD/mes frente a 155 con EKS de continuo,
  sobre un presupuesto de 50. EKS no se tiene, se alquila.
- **ADR 009** — Argo CD: qué se sincroniza solo y qué no. `prune` sí en las
  aplicaciones y **no** en los brokers, porque un PVC borrado son datos perdidos.
  Terraform crea AWS, Argo crea Kubernetes.
- **ADR 010** — Identidad sin llaves: OIDC para el pipeline, IRSA para los pods.
  Incluye el hallazgo que costó un despliegue: GitHub firma el subject con
  identificadores numéricos inmutables, no con `repo:owner/repo` como dice la
  documentación, y eso solo se descubre mirando CloudTrail.

**Estado real de F6**, para no engañarse: los tres bloques están escritos y el
demo público está **encendido, público y verificado punta a punta**. Lo que
**no** está aplicado es `terraform/aws-eks/`, así que ese módulo está validado y
planificado pero nunca ejecutado. F5 y F6 ya enseñaron tres veces que el código
sin aplicar esconde fallos: hay que dar por hecho que EKS tiene alguno.

## 11. Estructura del repositorio

```
event-lab/
├── CLAUDE.md            este archivo
├── README.md            abre con el problema, no con el stack
├── Makefile             make demo | up | down | logs | topics | clean
├── docs/adr/            decisiones con costo asumido explícito
├── apps/
│   ├── job-forge/       agregador Maven: contracts, api, worker, audit, ui
│   └── stream-guard/    desde F8
├── local/               solo para correr en tu máquina: compose y config
├── k8s/
│   ├── charts/          un chart Helm por demo
│   ├── overlays/        values por entorno; el pipeline solo toca image.tag
│   └── argocd/          una Application por demo
├── terraform/
│   ├── aws-eks/
│   ├── aws-ec2-k3s/
│   └── modules/
└── .github/workflows/
```

Los directorios se crean en la fase que los estrena, no antes.
