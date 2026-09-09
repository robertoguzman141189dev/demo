# 010 — Identidad sin llaves: OIDC para el pipeline, IRSA para los pods

- **Fecha:** 2026-09-08
- **Estado:** aceptado
- **Ámbito:** infraestructura

## Contexto

Dos cosas necesitan hablar con AWS y ninguna es una persona:

- El **pipeline**, para publicar imágenes en ECR.
- Los **pods**, para leer secretos y crear volúmenes.

La forma perezosa es la misma en los dos casos: crear un usuario IAM, generarle
unas access keys y pegarlas donde haga falta —secretos del repositorio, un
`Secret` de Kubernetes—. Funciona el primer día y deja una credencial permanente
que nadie rota, que no caduca, y que vale exactamente lo mismo el día que se
filtre en un log que el día que se creó.

Las dos tienen la misma solución, y por eso están en el mismo ADR: **que el
sistema demuestre quién es, en vez de enseñar una llave**.

## Decisión

### 1. GitHub Actions asume un rol por OIDC

GitHub firma, en cada ejecución, un token que dice de qué repositorio viene, de
qué rama y de qué evento. AWS registra a GitHub como proveedor de identidad,
acepta ese token y entrega credenciales temporales que caducan al terminar el
job. **No hay ningún secreto de AWS en el repositorio.**

Lo único que se guarda es el ARN del rol, y va como *variable*, no como secreto:
un ARN sin el token OIDC del repositorio correcto no sirve de nada.

### 2. La condición de confianza se fija por `sub` exacto

Es la línea que decide si esto es una defensa o un agujero:

```
token.actions.githubusercontent.com:sub
  = repo:robertoguzman141189dev@267489505/demo@1361658022:ref:refs/heads/main
```

Solo las ejecuciones sobre `main` de ese repositorio. Un comodín amplio
—`repo:mi-org/*` o, peor, `repo:*`— permite que **el workflow de cualquiera**
asuma el rol. No produce ningún error: simplemente funciona de más. Es un fallo
conocido y explotado.

#### El formato del `sub` no es el que dice la documentación

El primer intento falló, y la forma de fallar merece quedar escrita porque se
repetirá.

Prácticamente todas las guías dicen que el subject es
`repo:owner/repo:ref:refs/heads/main`. Con esa condición, el pipeline murió con:

```
Could not assume role with OIDC:
Not authorized to perform sts:AssumeRoleWithWebIdentity
```

Un mensaje que **no dice qué no encajaba**. La política era correcta línea por
línea, el proveedor estaba registrado y la variable llegaba bien.

Se resolvió preguntándole a AWS por el otro lado: **CloudTrail registra los
intentos fallidos junto con el subject que se presentó**.

```sh
aws cloudtrail lookup-events \
  --lookup-attributes AttributeKey=EventName,AttributeValue=AssumeRoleWithWebIdentity \
  --query 'Events[].[EventTime,Username]' --output text
```

Y ahí estaba lo que GitHub manda de verdad:

```
repo:robertoguzman141189dev@267489505/demo@1361658022:ref:refs/heads/main
```

GitHub intercala **identificadores numéricos inmutables** junto al nombre de la
cuenta y del repositorio. Y lo hace por una buena razón: los números no cambian
al renombrar, así que quien registre el nombre que tú abandonaste **no hereda tu
confianza**. Es más seguro que el formato antiguo y rompe todas las guías
escritas antes.

Los dos identificadores se obtienen así, y se verificaron antes de fijarlos:

```sh
curl -s https://api.github.com/repos/OWNER/REPO | jq '.id, .owner.id'
```

La lección que vale más que el dato concreto: **cuando una autenticación
federada falla, el emisor no te dice por qué, pero el receptor sí.** CloudTrail
es el sitio donde mirar.

### 3. Dos roles, no uno

- **Publicación**: confía en `ref:refs/heads/main`, y puede escribir en los
  cuatro repositorios de ECR. Nada más: ni borrar imágenes, ni crear
  repositorios.
- **Plan**: confía en `pull_request`, y es de solo lectura.

Separarlos no es ceremonia. Un pull request puede venir de cualquiera; darle el
rol de publicación sería regalar permiso de escritura a quien abra una rama.

### 4. Los pods asumen roles por IRSA

Mismo mecanismo, un piso más abajo. El cluster de EKS publica un emisor OIDC
propio; cada pod recibe un token que dice "soy el ServiceAccount X del namespace
Y", y AWS lo acepta como prueba de identidad. El token dura una hora y lo renueva
el kubelet.

El primer caso real ya está en el repositorio: el driver de EBS crea volúmenes en
AWS sin llevar encima ninguna credencial, con la confianza atada a
`system:serviceaccount:kube-system:ebs-csi-controller-sa`.

Aquí es donde los ServiceAccount dedicados de F5 dejan de ser burocracia: **el
ServiceAccount es el sujeto al que se le cuelga el rol**. Compartir uno significa
que todos los servicios acaban con la unión de los permisos del más goloso.

### 5. El pipeline no tiene credenciales del cluster

Su única escritura es un commit sobre `k8s/overlays/prod/`. Quien despliega es
Argo CD, desde dentro. El pipeline no puede tocar el cluster ni aunque el YAML se
lo pida, porque no tiene con qué.

## Costo asumido

- **Un `sub` mal escrito falla de forma opaca.** El pod o el job recibe un
  `AccessDenied` que no dice qué condición no casó. El error más común en IRSA es
  dejar el `https://` en el emisor dentro de la condición: nunca casa, y nada lo
  explica. Por eso el módulo expone el emisor ya recortado. Ya nos costó una
  ejecución fallida del pipeline por el formato del subject; está arriba.
- **Fijar los identificadores numéricos ata la confianza a un repositorio
  concreto para siempre**, que es lo que se quiere, pero significa que mover el
  proyecto a otra cuenta u otro repositorio exige tocar Terraform y aplicar. No
  basta con renombrar.
- **La confianza está atada a una rama concreta.** Publicar desde otra rama exige
  tocar Terraform y aplicar. Es fricción deliberada, pero es fricción.
- **`ReadOnlyAccess` en el rol del plan es más de lo estrictamente necesario.** Un
  plan necesita leer casi cualquier cosa para comparar con el estado, y acotarlo
  recurso a recurso daría una lista inmantenible que se rompe cada vez que se
  añade un tipo nuevo. Se acepta a cambio de que sea *solo* lectura.
- **El pipeline se commitea a sí mismo, y eso puede entrar en bucle.** Se frena
  por dos vías —`paths-ignore` sobre `k8s/overlays/**` y `[skip ci]` en el
  mensaje— porque una sola es un cinturón sin tirantes. Si alguien toca el filtro
  sin entenderlo, la segunda lo sigue frenando.
- **Los runners ARM son gratuitos hoy en repositorios públicos.** Si eso cambiara,
  o el repositorio pasara a privado, habría que pagarlos o volver a QEMU, que
  multiplica por diez el tiempo de un build de Maven.
- **Escribir en `main` desde el pipeline choca con la protección de rama.** Si
  algún día se protege `main`, el job del tag empezará a fallar y habrá que darle
  una excepción explícita.

## Alternativas descartadas

- **Access keys de un usuario IAM como secretos del repositorio.** Un paso menos
  y ninguna condición de confianza que escribir bien. Se descarta por lo obvio:
  es una credencial permanente en un sistema que no controlas, y su rotación
  depende de que alguien se acuerde.
- **Un solo rol para publicar y planificar.** Menos Terraform. Se descarta porque
  igualaría los permisos de un pull request y los de `main`.
- **Publicar imágenes desde el propio cluster**, con algo tipo Kaniko disparado
  por Argo. Evitaría dar permisos a GitHub. Se descarta porque mete el trabajo de
  construcción dentro del cluster que sirve el demo, y porque el historial de
  builds dejaría de estar donde está el código.
- **EKS Pod Identity** en vez de IRSA. Es el mecanismo más nuevo de AWS y no
  necesita proveedor OIDC. Se descarta en esta fase porque IRSA es lo que pide el
  `CLAUDE.md`, está mucho más documentado y es lo que sale en las entrevistas. Es
  la primera candidata a revisar más adelante.
- **Etiquetar las imágenes con el SHA corto.** Más legible en la interfaz de
  Argo. Se descarta porque siete caracteres colisionan, y una colisión aquí
  significa desplegar el binario equivocado.

## Consecuencias

- `terraform/bootstrap/` gana el proveedor OIDC de GitHub y los dos roles. Están
  ahí y no en la composición de EKS porque el pipeline publica imágenes exista o
  no exista un cluster en ese momento.
- Tras aplicar `bootstrap`, hay que dar de alta tres variables en GitHub
  —`AWS_ROLE_ARN`, `AWS_PLAN_ROLE_ARN` y `TF_STATE_BUCKET`—, que son valores no
  secretos que imprime `terraform output`.
- `.github/workflows/ci.yaml` construye en runners ARM porque todos los nodos son
  Graviton; emular con QEMU haría el build inviable.
- **Verificado punta a punta el 2026-09-09**, no solo escrito. Un push a `main`
  ejecutó las pruebas con Testcontainers sobre un runner ARM, asumió el rol por
  OIDC sin ningún secreto guardado, publicó las cuatro imágenes en ECR con el SHA
  completo del commit y escribió el tag de vuelta en
  `k8s/overlays/prod/job-forge/values.yaml`. El `[skip ci]` de ese commit evitó
  que se disparase a sí mismo.
- **Queda un hueco declarado:** el `Secret` con las credenciales de RabbitMQ se
  sigue creando a mano. El `CLAUDE.md` pide External Secrets contra Secrets
  Manager, que es el segundo consumidor natural de IRSA. No entra en esta entrega
  y es lo primero que falta por cerrar de F6.
