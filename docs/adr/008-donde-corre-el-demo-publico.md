# 008 — El demo público corre en k3s; EKS se alquila por horas

- **Fecha:** 2026-09-08
- **Estado:** aceptado
- **Ámbito:** infraestructura

## Contexto

El repositorio necesita dos cosas que tiran en direcciones opuestas.

Una, un **demo visitable**: un enlace en el README que funcione cuando alguien lo
abra, sin avisar y sin que nadie lo encienda antes. Eso significa encendido de
continuo.

Dos, **demostrar que sé operar EKS**: IRSA, grupo de nodos gestionado, más de un
nodo, `PodDisruptionBudget` que de verdad frene un drenaje. Eso significa EKS.

Encender EKS de continuo resuelve las dos a la vez, y es lo que casi todo el
mundo hace. El problema es el precio, y aquí no es una intuición: son los precios
vigentes en `us-east-1` consultados contra la API de precios de AWS el
2026-09-08.

| Concepto | Precio unitario | Al mes (730 h) |
|---|---|---|
| Control plane de EKS | 0,10 USD/h | 73,00 |
| NAT gateway | 0,045 USD/h + 0,045/GB | 32,85 |
| `t4g.medium` | 0,0336 USD/h | 24,53 |
| `t4g.small` | 0,0168 USD/h | 12,26 |
| EBS gp3 | 0,08 USD/GB-mes | 1,60 (20 GB) |

EKS encendido todo el mes con dos nodos y NAT: **unos 155 USD**. El presupuesto
acordado para este proyecto es de **50 USD/mes**. Solo el control plane ya se
come el 146 % del presupuesto antes de desplegar una sola aplicación.

## Decisión

**El demo público vive en una sola EC2 `t4g.medium` con k3s, encendida de
continuo. EKS existe como módulo completo pero se levanta bajo demanda y se
destruye al terminar.**

Coste fijo del demo: **unos 30 USD/mes** —instancia 24,53 + disco 1,60 + IP fija
~3,65—, con un 40 % de margen bajo la alerta.

Coste de EKS: **unos 0,70 USD por sesión de cuatro horas**. Ese es el encuadre
correcto y el que cambia la decisión: EKS no se *tiene*, se *alquila*. Aprenderlo
un fin de semana entero cuesta menos que un café.

Detalles que se derivan de elegir un nodo:

- **ARM (Graviton) y no x86**: la mitad de precio para la misma memoria, y las
  imágenes ya se construyen para esa plataforma.
- **Sin NAT gateway**: el nodo está en la subred pública con su IP. Son 33 USD/mes
  que no se pagan.
- **Sin SSH**: no se abre el puerto 22 ni existe par de claves. El acceso es por
  SSM Session Manager, que sale del nodo hacia AWS.
- **Traefik desactivado**: k3s lo trae por defecto y este repositorio usa
  ingress-nginx, que es lo probado en kind y lo que llevará el rate limiting de
  F7. Dos controladores peleando por el puerto 80 dan un fallo confuso.
- **El overlay de producción baja a un worker**: medido en kind, el despliegue
  completo pide unos 2,5 GiB solo en `requests`. En 4 GiB entra con uno; con dos
  va al límite.

## Costo asumido

- **No hay alta disponibilidad, y el repositorio no debe fingir que la hay.** Es
  un nodo. Si la instancia muere, el demo se cae hasta que alguien la recree.
- **Los datos viven en el disco de esa máquina.** k3s usa `local-path`: no hay
  volúmenes de red ni instantáneas. Perder la instancia es perder los datos de
  los brokers. Para datos sintéticos que se borran a los 30 minutos, aceptable.
- **La mitad del endurecimiento de F5 queda decorativa en este cluster.** Con un
  nodo, `topologySpreadConstraints` no puede satisfacerse y el
  `PodDisruptionBudget` no tiene nada que proteger, porque no hay drenaje
  posible. Siguen en el chart porque son correctos y porque sobre EKS sí actúan,
  pero quien mire solo el demo público no los verá funcionar.
- **k3s no es EKS.** Comparten API pero no comportamiento: el almacenamiento es
  distinto, el balanceador es `klipper` en vez de un ELB, y no hay IRSA. Un
  manifiesto que funciona aquí puede no funcionar allí, y al revés. Se mitiga
  desplegando el mismo chart en los dos.

  **Y esa diferencia ya costó un fallo concreto.** El kubelet de EKS trae un
  proveedor de credenciales que cambia el rol del nodo por un token de ECR sin
  que nadie se lo pida. k3s no lo trae, así que los cuatro pods se quedaron en
  `ErrImagePull` con un `no basic auth credentials` que no menciona IAM por
  ningún lado. Faltaban dos cosas: permiso de lectura de ECR en el rol de la
  instancia, y un mecanismo que convirtiera ese rol en un token.

  El binario del proveedor de credenciales de AWS no se publica como asset
  descargable, así que se resolvió con un temporizador de `systemd` que renueva
  el token como `Secret` cada seis horas —dura doce—. Aburrido a propósito: sin
  credenciales permanentes, sin imágenes extra y sin dependencias nuevas. Es
  exactamente el tipo de trabajo que EKS te ahorra y que conviene haber hecho una
  vez para saber qué te está ahorrando.
- **El Ingress enruta por nombre, no por IP.** Entrar por `http://<ip>/` devuelve
  404 aunque todo funcione detrás. Se resuelve con `nip.io`, que da un nombre
  público real sin registrar dominio. El día que haya dominio propio, es una línea.
- **Dos entornos que mantener.** Cada cambio de infraestructura hay que pensarlo
  dos veces. Es el precio de separar el escaparate del laboratorio.
- **La alerta de gasto avisa, no corta.** AWS Budgets manda un correo; no apaga
  nada. Si algo se queda encendido un sábado, el aviso llega y la factura sigue
  subiendo hasta que alguien actúe. El nombre "budget" induce a error.

## Alternativas descartadas

- **EKS encendido de continuo.** Una sola plataforma, sin dos entornos que
  mantener, y todo el endurecimiento de F5 actuando de verdad. Se descarta por
  precio: 155 USD/mes frente a un presupuesto de 50. Habría que triplicar el
  presupuesto para ganar coherencia en un demo que la mayoría de visitantes mira
  dos minutos.
- **EKS sin NAT, con los nodos en subredes públicas.** Baja a unos 122 USD/mes.
  Sigue siendo más del doble del presupuesto y empeora la postura de seguridad.
- **Solo EKS, encendido a ratos, sin demo permanente.** Coste casi cero. Se
  descarta porque el README dejaría de poder prometer un enlace visitable, y el
  repositorio pasaría de escaparate a repositorio de código.
- **Fargate para las aplicaciones.** Sin nodos que gestionar, pero sigue
  requiriendo el control plane de EKS —los 73 USD— y complica los `DaemonSet` y
  el almacenamiento local.
- **Capa gratuita de AWS.** No cubre EKS, y la de EC2 son 750 horas de `t2.micro`
  o `t3.micro` x86 con 1 GiB: ni siquiera arranca este despliegue.
- **Apagar el demo por la noche con un programador.** Ahorraría la mitad. Se
  descarta porque un enlace que funciona según la hora a la que lo abra un
  entrevistador es peor que no tenerlo.

## Consecuencias

- `terraform/bootstrap/` crea el estado remoto y **la alerta de gasto antes que
  nada**, para que exista antes que el primer recurso capaz de gastar.
- `terraform/modules/network/` nace con las subredes privadas y el NAT apagados
  por defecto: quien los quiera, los pide.
- La verificación de las `NetworkPolicy` que F5 dejó pendiente —el CNI de kind no
  las aplica— puede hacerse ya en este demo: k3s trae un controlador de
  NetworkPolicy activado por defecto. No hace falta esperar a EKS.
- El `terraform destroy` de `aws-eks/` forma parte del procedimiento normal, no
  es una excepción. Queda documentado en el README.
