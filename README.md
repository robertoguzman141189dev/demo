# event-lab

Dos preguntas que casi nunca se responden bien en una entrevista de arquitectura:

**¿Qué haces cuando una unidad de trabajo falla a la mitad y nadie se enteró?**
Un worker recibe una tarea, empieza a procesarla y el pod muere. O el proceso
tarda de más. O falla por una razón transitoria que en 30 segundos ya no existe.
Reintentar de inmediato empeora las cosas; reintentar para siempre tampoco es
reintentar. Y después de N intentos alguien tiene que poder mirar el mensaje que
quedó atorado y decidir qué hacer con él.

**¿Cómo decides en milisegundos usando el comportamiento reciente de un cliente
sin preguntarle a una base de datos?** Cuando llega una transacción, la respuesta
depende de las últimas diez que hizo esa misma persona. Consultar un almacén
remoto en el camino crítico convierte un problema de streaming en un problema de
latencia de red.

Son dos problemas distintos y **necesitan brokers distintos**. Este repositorio
construye una demostración funcional de cada uno, con las palancas expuestas para
que cualquiera pueda romper el sistema en vivo y ver cómo se recupera.

---

## `job-forge` — la entrega es el producto

Subes un archivo, se descompone en tareas, un pool de workers las procesa. Desde
el panel puedes subir la tasa de fallo de los workers, cambiar el prefetch, matar
un worker a media tarea o reprocesar lo que cayó a la cola de mensajes muertos.

RabbitMQ está aquí por lo que un log distribuido no da: acuse de recibo por
unidad de trabajo, TTL, prioridades y dead-letter routing. El backoff exponencial
sale de encadenar colas de espera con TTL y dead-letter exchange — sin scheduler
externo, sin base de datos, sin un solo `Thread.sleep`.

Kafka aparece aquí, pero en su papel correcto: log de auditoría inmutable y un
topic compactado desde el que se reconstruye el estado sin persistencia propia.

## `stream-guard` — el estado vive dentro del consumidor

Un flujo de transacciones sintéticas y tres detectores de fraude que deciden con
lo que ya tienen en memoria local. El particionamiento por cliente garantiza que
todas las operaciones de una misma persona caen en la misma instancia, así que el
estado necesario para decidir siempre está a un acceso local de distancia.

Kafka está aquí por particionamiento con orden garantizado, estado colocalizado y
replay — no por volumen. Puedes matar un pod del motor y ver el rebalance, o
rebobinar el offset y reprocesar el día entero.

RabbitMQ aparece aquí, pero en su papel correcto: las órdenes de notificación
push, que sí son unidades de trabajo con acuse de recibo.

---

## Estado

En construcción. La fase actual y el plan completo están en
[`CLAUDE.md`](CLAUDE.md); las decisiones y su costo asumido, en
[`docs/adr/`](docs/adr/).

## Correrlo en local

```sh
make demo
```

Levanta los brokers y la observabilidad en Docker. `make help` lista el resto.

## Correrlo en Kubernetes

```sh
make k8s
```

Crea un cluster local con kind, construye las cuatro imágenes, las carga en el
nodo y despliega los dos charts. Al terminar, el panel queda en
http://job-forge.localtest.me.

Necesita Docker, `kind` y `helm`. `make kind-down` lo borra todo y no deja rastro.

**Es esto o `make demo`, no los dos a la vez.** Cada uno levanta su propio par de
brokers, y los dos viven dentro de la misma máquina virtual de Docker. En un
portátil de 16 GB, tenerlos encendidos a la vez satura la VM y el cluster deja de
responder. Antes de `make k8s`, un `make down`.

## Aviso

Todos los datos son sintéticos. Los paneles son públicos y anónimos, sin
autenticación, con límites de uso por sesión y globales. Los archivos que subas
se borran a los 30 minutos.
