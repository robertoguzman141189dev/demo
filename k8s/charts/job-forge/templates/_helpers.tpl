{{/*
Bloques compartidos por los cuatro servicios. Lo que está aquí es lo que debe ser
idéntico en todos: si el endurecimiento del contenedor se copiara y pegara en
cuatro plantillas, la próxima corrección se aplicaría en tres.
*/}}

{{- define "job-forge.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "job-forge.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "job-forge.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Etiquetas comunes. Recibe un dict con root y component.
*/}}
{{- define "job-forge.labels" -}}
helm.sh/chart: {{ include "job-forge.chart" .root }}
app.kubernetes.io/name: {{ include "job-forge.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
app.kubernetes.io/part-of: job-forge
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
{{- end -}}

{{/*
Etiquetas de selección: solo las que identifican al pod, y nunca la versión.
Un selector que incluyera el tag de imagen sería inmutable de facto y rompería
el siguiente despliegue.
*/}}
{{- define "job-forge.selectorLabels" -}}
app.kubernetes.io/name: {{ include "job-forge.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/*
Referencia completa de la imagen. El tag es obligatorio: si el values del entorno
no lo trae, esto revienta al renderizar en vez de desplegar algo arbitrario
(ADR 002). Recibe un dict con root, svc y component.
*/}}
{{- define "job-forge.image" -}}
{{- $tag := required (printf "job-forge: falta image.tag del componente '%s'. El pipeline lo escribe en k8s/overlays/<entorno>/job-forge/values.yaml; un despliegue sin tag no debe adivinar una version." .component) .svc.image.tag -}}
{{- printf "%s%s:%s" .root.Values.image.registry .svc.image.repository $tag -}}
{{- end -}}

{{/*
securityContext del pod. Recibe un dict con uid.

runAsNonRoot obliga al kubelet a comprobar que el usuario no es root; como no
puede leer el /etc/passwd de la imagen, el uid tiene que venir numérico. Por eso
se repite aquí lo que el Dockerfile ya declara: son dos comprobaciones
independientes y la de Kubernetes no ve la del Dockerfile.
*/}}
{{- define "job-forge.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: {{ .uid }}
runAsGroup: {{ .uid }}
fsGroup: {{ .uid }}
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{/*
securityContext del contenedor. Estos cuatro procesos escriben en /tmp y en
ningún otro sitio, así que la raíz va en solo lectura y /tmp se monta aparte.
*/}}
{{- define "job-forge.containerSecurityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
privileged: false
capabilities:
  drop:
    - ALL
{{- end -}}

{{/*
Variables de entorno comunes a los tres servicios Java.

Las credenciales llegan por secretKeyRef y no por valor: así el manifiesto
renderizado se puede enseñar entero sin filtrar nada.
*/}}
{{- define "job-forge.brokerEnv" -}}
- name: SERVER_PORT
  value: "8080"
- name: RABBITMQ_HOST
  value: {{ .Values.platform.rabbitmq.host | quote }}
- name: RABBITMQ_PORT
  value: {{ .Values.platform.rabbitmq.port | quote }}
- name: RABBITMQ_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ .Values.platform.rabbitmq.credentialsSecret }}
      key: {{ .Values.platform.rabbitmq.usernameKey }}
- name: RABBITMQ_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Values.platform.rabbitmq.credentialsSecret }}
      key: {{ .Values.platform.rabbitmq.passwordKey }}
- name: KAFKA_BOOTSTRAP
  value: {{ .Values.platform.kafka.bootstrap | quote }}
{{- end -}}

{{/*
Reparto entre nodos. Recibe un dict con root y component.

whenUnsatisfiable: ScheduleAnyway y no DoNotSchedule: con un nodo solo, la
versión estricta deja los pods en Pending para siempre. Preferimos que reparta
cuando pueda y que no bloquee cuando no.
*/}}
{{- define "job-forge.topologySpread" -}}
{{- if .root.Values.topologySpread.enabled }}
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: ScheduleAnyway
    labelSelector:
      matchLabels:
        {{- include "job-forge.selectorLabels" (dict "root" .root "component" .component) | nindent 8 }}
{{- end }}
{{- end -}}
