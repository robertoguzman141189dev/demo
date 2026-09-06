# syntax=docker/dockerfile:1.7
#
# Imagen de los tres servicios Java de job-forge.
#
# Un solo Dockerfile y tres imágenes, elegidas con --build-arg MODULE. El
# reactor de Maven es uno: tener tres ficheros casi idénticos solo garantiza que
# tarde o temprano se desincronicen y que la corrección de seguridad que se
# aplica a uno se olvide en los otros.
#
#   docker build --build-arg MODULE=worker -t job-forge-worker:dev apps/job-forge
#
# MODULE es el DIRECTORIO del módulo (worker, api, audit), no su artifactId:
# -pl de Maven resuelve rutas, y pasarle "job-forge-worker" falla con un
# "Could not find the selected project in the reactor" que no explica por qué.
#
# El contexto es apps/job-forge porque contracts es dependencia de los tres y
# tiene que entrar en el build. Lo que sobra lo recorta .dockerignore.

# ----------------------------------------------------------------- build -----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Los POM primero y solos, antes que el código. Mientras no cambie ninguno,
# Docker reutiliza esta capa y editar una clase no vuelve a resolver el árbol de
# dependencias entero.
COPY pom.xml .
COPY contracts/pom.xml contracts/
COPY worker/pom.xml worker/
COPY api/pom.xml api/
COPY audit/pom.xml audit/

COPY contracts/src contracts/src
COPY worker/src worker/src
COPY api/src api/src
COPY audit/src audit/src

ARG MODULE
# El repositorio local de Maven va en una caché de BuildKit, no en una capa: se
# reaprovecha entre builds sin engordar la imagen ni un byte.
#
# -pl compila el módulo pedido y -am arrastra contracts, del que dependen los
# tres. Las pruebas no se ejecutan aquí a propósito: las de integración levantan
# Testcontainers, y eso exige un Docker dentro del build. El pipeline las corre
# antes de construir la imagen (F6); si fallan, no se llega hasta este punto.
# El glob de la copia no nombra el artefacto: spring-boot-maven-plugin deja el
# jar ejecutable y un .jar.original al lado, y *.jar solo casa con el primero.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl "${MODULE}" -am -DskipTests package \
    && cp "${MODULE}"/target/*.jar /build/app.jar

# --------------------------------------------------------------- runtime -----
FROM eclipse-temurin:21-jre-alpine AS runtime

# Kubernetes no lee el /etc/passwd de la imagen: si securityContext lleva
# runAsNonRoot y el USER del Dockerfile es un nombre, el kubelet no puede
# comprobar que no sea root y se niega a arrancar el contenedor. Por eso el uid
# es numérico aquí y se repite explícitamente en el chart.
RUN addgroup -g 10001 -S app && adduser -u 10001 -S app -G app

WORKDIR /app
COPY --from=build --chown=10001:10001 /build/app.jar app.jar
USER 10001:10001

# Los tres servicios escuchan en el mismo puerto dentro del cluster; el 8080,
# 8081 y 8082 de local/ existen solo para no chocar en tu máquina.
ENV SERVER_PORT=8080
EXPOSE 8080

# MaxRAMPercentage y no -Xmx: el heap se dimensiona contra el límite del cgroup,
# así que cambiar resources.limits.memory en el chart basta para reajustarlo sin
# reconstruir la imagen.
#
# ExitOnOutOfMemoryError no es opcional en un consumidor. Sin esa bandera, un
# OutOfMemoryError mata el hilo que lo sufre pero deja la JVM en pie: el proceso
# responde a la liveness, Kubernetes lo da por sano, y tienes un pod que consume
# memoria y no procesa nada. Con ella, el proceso muere y lo reinician.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "/app/app.jar"]
