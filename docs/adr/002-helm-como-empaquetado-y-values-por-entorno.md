# 002 — Helm es la unidad de empaquetado; `k8s/overlays/` solo lleva values

- **Fecha:** 2026-09-03
- **Estado:** aceptado
- **Ámbito:** infraestructura

## Contexto

El diseño original pedía dos cosas que pertenecen a mecanismos distintos: charts
de Helm para empaquetar cada demo, y que el pipeline actualizara el tag de imagen
en `k8s/overlays/prod/` —vocabulario de Kustomize—.

Mantener los dos a la vez le da a Argo CD dos fuentes de verdad para el mismo
Deployment. Cuando el chart y el overlay discrepan, el resultado depende del orden
de renderizado, y eso se descubre con un rollback fallido a las tres de la mañana.

Hay además una restricción de GitOps que no es negociable: el pipeline no toca el
cluster. Su única escritura es un commit. Por lo tanto tiene que existir un
archivo, y uno solo, cuyo cambio signifique "despliega esta versión".

## Decisión

Helm es la única unidad de empaquetado. Un chart por demo en `k8s/charts/`.

`k8s/overlays/<entorno>/<demo>/values.yaml` deja de ser un overlay de Kustomize y
pasa a ser el archivo de `values` de ese entorno. El pipeline solo escribe ahí, y
solo el campo `image.tag`, con el SHA del commit.

Cada `Application` de Argo CD apunta al chart y al values de su entorno. Las
dependencias de terceros (RabbitMQ, Kafka, Prometheus, Grafana, KEDA) entran como
subcharts o como Applications propias en el namespace `platform`.

Se conserva el nombre `overlays/` aunque ya no sea Kustomize, porque expresa bien
la intención: lo que se superpone al chart por entorno.

## Costo asumido

- **Se paga el templating de Helm.** El YAML con `{{ }}` es menos legible que un
  manifiesto plano, y un error de indentación en un template se manifiesta como un
  recurso mal formado en el cluster, no como un error de sintaxis en el editor. Se
  mitiga con `helm template` en CI antes de commitear el tag.
- **El diff en el repositorio no es el diff aplicado.** Al revisar un commit se ve
  `image.tag: abc123`, no los treinta manifiestos que cambian. Argo CD sí muestra
  el diff renderizado, pero deja de estar en el historial de git.
- **Renunciamos a la composición por parches de Kustomize**, que es más natural
  para variaciones pequeñas entre entornos.
- **Un chart mal versionado puede desplegar algo distinto a lo probado.** Obliga a
  versionar el chart junto con el código y a no usar rangos en las dependencias.

## Alternativas descartadas

- **Kustomize puro, sin Helm.** Más simple y sin templating, con `kustomize edit
  set image` como punto de mutación. Se descarta porque instalar los brokers y las
  dependencias de terceros con Kustomize implica reempaquetar a mano charts que ya
  existen y están mantenidos.
- **Helm renderizado a manifiestos planos commiteados** (`helm template` en CI, el
  YAML resultante al repositorio). Diffs perfectamente auditables y el mejor
  historial de los tres. Se descarta por el ruido: cada despliegue genera un commit
  de cientos de líneas autogeneradas en un repositorio cuya audiencia es humana.
- **Argo CD Image Updater**, que actualiza el tag sin pasar por el pipeline. Menos
  pasos, pero el historial de despliegues deja de ser el historial de git, que es
  la propiedad por la que se eligió GitOps.

## Consecuencias

- `k8s/charts/` y `k8s/overlays/` nacen en F5; `k8s/argocd/` en F6.
- El workflow de CI de F6 modifica exactamente una línea por despliegue y no tiene
  credenciales del cluster, solo permiso de push al repositorio y OIDC contra ECR.
- Ningún chart usa `:latest`. El `image.tag` por defecto en `values.yaml` queda
  vacío a propósito, para que un despliegue sin tag falle en vez de desplegar algo
  arbitrario.
