# 009 — Argo CD: qué se sincroniza solo y qué no

- **Fecha:** 2026-09-08
- **Estado:** aceptado
- **Ámbito:** infraestructura

## Contexto

Hasta F5 los despliegues se empujaban: alguien —una persona o un pipeline—
ejecutaba `helm upgrade` contra el cluster. Eso obliga a que quien despliega
tenga credenciales del cluster, y hace que el historial de despliegues viva en
los logs del pipeline en vez de en git.

Argo CD invierte la dirección: vive dentro del cluster, observa el repositorio y
trabaja para que el cluster se parezca a lo que dice git. El pipeline deja de
tener credenciales del cluster; su única escritura es un commit.

Eso resuelve el problema y crea uno nuevo: **un agente que aplica cambios solo es
un agente que puede borrar cosas solo**. Las decisiones de aquí abajo son sobre
dónde ponerle el freno.

## Decisión

### 1. `prune` sí en las aplicaciones, no en los brokers

`prune` borra del cluster lo que desaparece del repositorio. Es lo que hace que
git sea la verdad y no solo una sugerencia.

En `job-forge` está **activado**: ahí todo es efímero y un Deployment que
desaparece del repositorio debe desaparecer del cluster.

En `platform` está **desactivado**, y es la decisión más importante de este ADR.
Los brokers son StatefulSets con volúmenes. Un `prune` mal disparado —un fichero
movido, una ruta mal escrita en un refactor— puede llevarse un
`PersistentVolumeClaim`, y un PVC borrado son datos perdidos sin vuelta atrás. Se
prefiere quedarse con recursos huérfanos: visibles, molestos y recuperables.

### 2. `selfHeal` en todo

Revierte lo que alguien cambie a mano en el cluster. Es lo que impide que la
configuración real se aleje del repositorio a base de arreglos rápidos.

### 3. Patrón "app of apps"

Una `Application` raíz cuyo contenido son otras `Application`. Se aplica una vez
y a partir de ahí añadir un demo es añadir un archivo y hacer commit.

Después de aplicarla, **el único `kubectl apply` que este proyecto ejecuta contra
el cluster ya se ejecutó**. Ese es el objetivo.

### 4. Un `AppProject` propio, no el `default`

El proyecto por defecto permite desplegar cualquier cosa, en cualquier namespace,
desde cualquier repositorio. `event-lab` acota los tres orígenes permitidos, los
tres namespaces de destino y una lista corta de recursos de ámbito de cluster.

No protege de alguien que ya controle Argo CD. Protege del error honesto y limita
el radio de un repositorio comprometido, que es la amenaza realista.

### 5. La interfaz de Argo CD no se expone

Sin `Ingress`, `Service` de tipo `ClusterIP`. El nodo del demo tiene los puertos
80 y 443 abiertos al mundo, y quien controla Argo CD controla todo lo que se
despliega. Se accede por `port-forward` a través de una sesión de SSM, que ya
exige estar autenticado contra AWS.

### 6. Terraform no crea objetos de Kubernetes

La frontera es literal: **Terraform crea AWS, Argo CD crea Kubernetes.** La
`StorageClass` de gp3 que necesita EKS es un objeto de Kubernetes y por tanto la
despliega Argo, aunque fuese cómodo crearla en el mismo módulo que el cluster.

Mezclarlo obliga a configurar el proveedor de Kubernetes con credenciales que
produce el propio apply que lo configura. Ese ciclo funciona al crear y falla al
destruir, dejando `terraform destroy` colgados que hay que desenredar a mano.

### 7. Versiones fijadas, también en los charts de terceros

`ingress-nginx` y el propio chart de Argo CD van con versión exacta, no con
rango. Un componente que se actualiza solo puede cambiar el enrutado o la forma
de sincronizar sin que nadie toque el repositorio, que es exactamente lo que
GitOps existe para impedir.

## Costo asumido

- **`selfHeal` desconcierta durante un incidente.** Un arreglo urgente aplicado
  con `kubectl` dura hasta la siguiente reconciliación —aquí, 60 segundos— y
  luego desaparece. Quien no sepa que está activado va a pensar que el cluster
  está embrujado. Se mitiga sabiéndolo; no hay forma elegante de tener las dos
  cosas.
- **Sin `prune` en los brokers, el repositorio deja de ser la verdad completa
  allí.** Puede haber recursos vivos que ya no estén en git, y limpiarlos es
  manual. Es el precio de no arriesgar datos.
- **El "app of apps" propaga los errores igual de rápido que los aciertos.** Un
  commit malo en `k8s/argocd/applications/` se aplica en menos de un minuto sin
  que nadie lo mire. El freno es la revisión del pull request, no el cluster.
- **El primer despliegue falla a propósito.** Con `image.registry` e `image.tag`
  vacíos, la `Application` de `job-forge` no renderiza hasta que el pipeline
  escriba el tag. Es coherente con el ADR 002 —un despliegue sin versión no debe
  adivinar— pero significa que quien clone esto y lo despliegue verá rojo antes
  que verde, y conviene que lo sepa de antemano.
- **Las versiones fijadas envejecen.** Nadie avisa de que salió una versión con
  un parche de seguridad; hay que mirarlo. Es deuda consciente a cambio de
  reproducibilidad.
- **Argo CD consume recursos en un nodo que va justo.** Incluso recortado
  —sin Dex, sin notificaciones, sin ApplicationSet— pide unos 600 MiB de los
  4 GiB de la máquina. Es parte de por qué el demo público baja a un worker.

## Alternativas descartadas

- **`kubectl apply` o `helm upgrade` desde el pipeline.** Mucho más simple y sin
  un componente más que mantener. Se descarta porque obliga a dar credenciales
  del cluster al pipeline, que es justo lo que el diseño quiere evitar, y porque
  el historial de despliegues dejaría de ser el historial de git.
- **Flux** en vez de Argo CD. Más ligero y más idiomático en Kubernetes puro. Se
  descarta porque Argo CD está en el stack fijo y porque su interfaz visual vale
  mucho en un repositorio cuyo objetivo es que alguien *vea* funcionar el
  mecanismo.
- **Exponer la interfaz de Argo con autenticación.** Sería enseñable en una
  entrevista sin necesidad de túnel. Se descarta: es la consola de despliegue de
  un cluster con endpoints públicos, y ninguna demostración justifica ese riesgo.
- **`prune` activado en todas partes, con los volúmenes protegidos por una
  política de retención.** Más coherente. Se descarta porque la protección
  depende de que cada PVC esté bien anotado, y un olvido se paga con datos.
- **Sincronización manual, solo con `selfHeal`.** Cada despliegue exigiría un
  clic. Se descarta porque convierte el demo en algo que hay que atender.

## Consecuencias

- `k8s/argocd/` nace en esta entrega con el proyecto, la aplicación raíz y tres
  aplicaciones: `platform`, `job-forge` e `ingress-nginx`.
- `k8s/overlays/prod/` nace con los values del demo público, recortados a **un
  worker**: el nodo son 4 GiB compartidos con dos brokers, el controlador de
  Ingress y el propio Argo CD.
- El `Secret` de credenciales de RabbitMQ se sigue creando a mano, como en kind.
  Sustituirlo por External Secrets contra Secrets Manager es trabajo de la
  siguiente entrega, porque necesita IRSA.
- `make argocd-install` y `make argocd-bootstrap` son los dos únicos comandos de
  este proyecto que tocan el cluster directamente.
