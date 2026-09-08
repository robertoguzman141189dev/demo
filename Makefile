# event-lab — atajos para el entorno local.
# Todo lo que vive en local/ existe solo para tu máquina; nada de esto se
# despliega. En el cluster los brokers los instala Helm en el namespace platform.

COMPOSE := docker compose -f local/docker-compose.yml

# El `java` del PATH puede ser cualquiera; los módulos exigen 21. En macOS se
# resuelve así, en Linux y en CI manda el JAVA_HOME que ya venga puesto.
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null)
MVN := JAVA_HOME=$(JAVA_HOME) mvn -B

# ---------------------------------------------------------------- F5: kind ---
KIND_CLUSTER := event-lab
KUBECTL := kubectl --context kind-$(KIND_CLUSTER)
HELM := helm --kube-context kind-$(KIND_CLUSTER)
# Se fija la versión del controlador de Ingress: "main" cambia bajo los pies y
# convierte un despliegue reproducible en una ruleta.
INGRESS_NGINX_VERSION := controller-v1.15.1
IMAGES := api worker audit ui

# --------------------------------------------------------------- F6: argo ---
# Version del chart de Argo CD, fijada. Un agente de GitOps que se actualiza solo
# puede cambiar como se sincroniza todo lo demas sin que nadie toque el repositorio.
ARGOCD_CHART_VERSION := 9.1.7

.DEFAULT_GOAL := help
.PHONY: help demo up down restart ps logs topics urls clean nuke build test verify run-api run-worker run-audit run-ui estado \
        kind-up kind-down kind-ingress kind-secret images kind-load deploy k8s k8s-status k8s-urls \
        argocd-install argocd-bootstrap argocd-password argocd-ui argocd-status

help: ## Lista los objetivos disponibles
	@grep -hE '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

demo: up urls ## Levanta todo y te dice a dónde entrar

up: ## Levanta brokers y observabilidad, y espera a que estén sanos
	$(COMPOSE) up -d --wait

down: ## Apaga los contenedores conservando los volúmenes
	$(COMPOSE) down

restart: down up ## Ciclo completo de apagado y encendido

ps: ## Estado de los contenedores
	$(COMPOSE) ps

logs: ## Sigue los logs de todos los servicios (usa s=nombre para uno solo)
	$(COMPOSE) logs -f $(s)

# KAFKA_OPTS= evita que esta JVM cargue el javaagent del broker y choque contra
# el puerto 7071, que ya está ocupado.
topics: ## Lista los topics de Kafka
	$(COMPOSE) exec -e KAFKA_OPTS= kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

urls: ## Muestra las consolas locales
	@echo ""
	@echo "  RabbitMQ    http://localhost:15672   (guest / guest)"
	@echo "  Prometheus  http://localhost:9090    /targets para diagnosticar"
	@echo "  Grafana     http://localhost:3000    (admin / admin)"
	@echo ""
	@echo "  Kafka       localhost:29092          bootstrap DESDE TU MÁQUINA"
	@echo "              kafka:9092               bootstrap desde otro contenedor"
	@echo "  AMQP        localhost:5672"
	@echo ""

build: ## Compila job-forge sin ejecutar pruebas
	$(MVN) -f apps/job-forge/pom.xml -DskipTests package

test: ## Pruebas unitarias: rápidas, sin broker
	$(MVN) -f apps/job-forge/pom.xml test

verify: ## Todo, incluidas las pruebas de integración (levanta contenedores)
	$(MVN) -f apps/job-forge/pom.xml verify

run-api: ## Arranca el API de job-forge en el 8080 (necesita make up)
	$(MVN) -f apps/job-forge/api/pom.xml spring-boot:run

run-worker: ## Arranca un worker en el 8081 (necesita make up)
	$(MVN) -f apps/job-forge/worker/pom.xml spring-boot:run

run-audit: ## Arranca el proyector en el 8082 (necesita make up)
	$(MVN) -f apps/job-forge/audit/pom.xml spring-boot:run

run-ui: ## Arranca el panel en el 4200 (proxy al api del 8090)
	cd apps/job-forge/ui && npm start

estado: ## Vuelca el topic compactado: el estado actual de cada tarea
	$(COMPOSE) exec -e KAFKA_OPTS= kafka /opt/kafka/bin/kafka-console-consumer.sh \
		--bootstrap-server localhost:9092 --topic trabajos.estado \
		--from-beginning --property print.key=true --timeout-ms 5000

# ------------------------------------------------------------ F5: en kind ----
# Todo lo de abajo despliega job-forge en un cluster de verdad. `make k8s` hace
# el recorrido completo desde cero; el resto son los pasos sueltos.

k8s: kind-up kind-ingress kind-secret images kind-load deploy k8s-urls ## Despliegue completo en kind, desde cero

kind-up: ## Crea el cluster local de kind
	kind create cluster --config local/kind/cluster.yaml
	$(KUBECTL) wait --for=condition=Ready node --all --timeout=180s

kind-ingress: ## Instala el controlador de Ingress y espera a que esté listo
	$(KUBECTL) apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/$(INGRESS_NGINX_VERSION)/deploy/static/provider/kind/deploy.yaml
	$(KUBECTL) -n ingress-nginx wait --for=condition=Available deployment/ingress-nginx-controller --timeout=300s
	# Esperar al Deployment NO basta, y esto cuesta un despliegue fallido
	# aprenderlo: ingress-nginx registra un webhook de admisión que valida cada
	# Ingress que se crea. El Deployment puede figurar como Available mientras el
	# endpoint del webhook todavía rechaza conexiones, y entonces el helm install
	# del chart muere con un "failed calling webhook ... connection refused" que
	# no menciona por ningún lado que el problema sea de arranque.
	$(KUBECTL) -n ingress-nginx wait --for=condition=Ready pod \
		--selector=app.kubernetes.io/component=controller --timeout=300s

# La contraseña se genera aquí y no se escribe en ningún archivo del repositorio.
# En AWS este Secret lo materializa External Secrets desde Secrets Manager; el
# mecanismo cambia, el contrato —un Secret con username y password— no.
kind-secret: ## Crea el Secret de RabbitMQ con una contraseña aleatoria
	@$(KUBECTL) create namespace platform --dry-run=client -o yaml | $(KUBECTL) apply -f -
	@$(KUBECTL) create namespace job-forge --dry-run=client -o yaml | $(KUBECTL) apply -f -
	@PASS=$$(openssl rand -hex 16); \
	for NS in platform job-forge; do \
	  $(KUBECTL) -n $$NS create secret generic job-forge-rabbitmq \
	    --from-literal=username=job-forge --from-literal=password=$$PASS \
	    --dry-run=client -o yaml | $(KUBECTL) apply -f -; \
	done

images: ## Construye las cuatro imágenes con el tag dev
	docker build --build-arg MODULE=api   -t job-forge-api:dev   apps/job-forge
	docker build --build-arg MODULE=worker -t job-forge-worker:dev apps/job-forge
	docker build --build-arg MODULE=audit -t job-forge-audit:dev apps/job-forge
	docker build -t job-forge-ui:dev apps/job-forge/ui

# kind no ve el registro local de Docker: las imágenes hay que meterlas dentro
# del nodo. Sin esto, los pods se quedan en ImagePullBackOff intentando bajar de
# Docker Hub una imagen que solo existe en tu máquina.
kind-load: ## Carga las imágenes dentro del nodo de kind
	@for I in $(IMAGES); do kind load docker-image job-forge-$$I:dev --name $(KIND_CLUSTER); done

deploy: ## Instala los brokers y job-forge con los values de local
	$(HELM) upgrade --install platform k8s/charts/platform --namespace platform \
		-f k8s/overlays/local/platform/values.yaml --wait --timeout 10m
	$(HELM) upgrade --install job-forge k8s/charts/job-forge --namespace job-forge \
		-f k8s/overlays/local/job-forge/values.yaml --wait --timeout 10m

k8s-status: ## Estado de los pods de los dos namespaces
	@$(KUBECTL) -n platform get pods -o wide
	@echo ""
	@$(KUBECTL) -n job-forge get pods -o wide

k8s-urls: ## Dónde entrar una vez desplegado
	@echo ""
	@echo "  Panel   http://job-forge.localtest.me    (localtest.me resuelve a 127.0.0.1)"
	@echo ""
	@echo "  Consola de RabbitMQ, si hace falta mirar dentro:"
	@echo "    kubectl -n platform port-forward svc/rabbitmq 15672:15672"
	@echo "    usuario job-forge; la contraseña está en el Secret job-forge-rabbitmq"
	@echo ""

kind-down: ## Borra el cluster de kind entero
	kind delete cluster --name $(KIND_CLUSTER)

# ------------------------------------------------------------- F6: Argo CD ---
# Estos objetivos usan el contexto de kubectl ACTIVO, no el de kind, porque valen
# igual para el nodo de k3s del demo publico. Comprueba con `kubectl config
# current-context` antes de lanzarlos.

# El unico `helm install` contra el cluster que ejecuta este proyecto. Alguien
# tiene que meter dentro al primer agente de GitOps; a partir de aqui, todo lo
# demas lo despliega el.
argocd-install: ## Instala Argo CD en el cluster activo
	helm repo add argo https://argoproj.github.io/argo-helm
	helm repo update argo
	helm upgrade --install argocd argo/argo-cd \
		--version $(ARGOCD_CHART_VERSION) \
		--namespace argocd --create-namespace \
		-f k8s/argocd/install/values.yaml \
		--wait --timeout 10m

# El unico `kubectl apply` que este proyecto ejecuta contra el cluster. Despues
# de esto, Argo descubre solo todo lo que haya en k8s/argocd/applications/.
argocd-bootstrap: ## Aplica el proyecto y la aplicacion raiz; a partir de aqui manda git
	kubectl apply -f k8s/argocd/project.yaml
	kubectl apply -f k8s/argocd/root.yaml

argocd-password: ## Contrasena inicial del administrador
	@kubectl -n argocd get secret argocd-initial-admin-secret \
		-o jsonpath='{.data.password}' | base64 -d; echo ""

# Sin Ingress a proposito: quien controla Argo CD controla todo lo que se
# despliega. Se llega por un tunel local, no por internet.
argocd-ui: ## Abre la interfaz en https://localhost:8081 (usuario admin)
	@echo "  http://localhost:8081   usuario admin, contrasena: make argocd-password"
	kubectl -n argocd port-forward svc/argocd-server 8081:80

argocd-status: ## Estado de sincronizacion de todas las aplicaciones
	@kubectl -n argocd get applications.argoproj.io \
		-o custom-columns=NOMBRE:.metadata.name,SYNC:.status.sync.status,SALUD:.status.health.status

clean: ## Apaga y borra los volúmenes: brokers vacíos, estado perdido
	$(COMPOSE) down -v

nuke: clean ## clean + borra también las imágenes descargadas
	$(COMPOSE) down --rmi all --remove-orphans
