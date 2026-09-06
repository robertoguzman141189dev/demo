# event-lab — atajos para el entorno local.
# Todo lo que vive en local/ existe solo para tu máquina; nada de esto se
# despliega. En el cluster los brokers los instala Helm en el namespace platform.

COMPOSE := docker compose -f local/docker-compose.yml

# El `java` del PATH puede ser cualquiera; los módulos exigen 21. En macOS se
# resuelve así, en Linux y en CI manda el JAVA_HOME que ya venga puesto.
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null)
MVN := JAVA_HOME=$(JAVA_HOME) mvn -B

.DEFAULT_GOAL := help
.PHONY: help demo up down restart ps logs topics urls clean nuke build test verify run-api run-worker run-audit run-ui estado

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

clean: ## Apaga y borra los volúmenes: brokers vacíos, estado perdido
	$(COMPOSE) down -v

nuke: clean ## clean + borra también las imágenes descargadas
	$(COMPOSE) down --rmi all --remove-orphans
