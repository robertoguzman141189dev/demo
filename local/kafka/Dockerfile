# Kafka no expone métricas en formato Prometheus: las publica por JMX, que es un
# protocolo de la JVM. El JMX exporter es el traductor. Va como javaagent dentro
# del propio proceso del broker —no como contenedor aparte— para no tener que
# abrir JMX remoto, que en Docker es una fuente conocida de dolor por el
# hostname de RMI.
#
# Esta imagen existe solo para el entorno local. En el cluster, el chart de Kafka
# resuelve esto por su cuenta.

ARG KAFKA_VERSION=4.3.1
FROM apache/kafka:${KAFKA_VERSION}

ARG JMX_AGENT_VERSION=1.0.1

# uid 1000 es el usuario con el que corre el broker en esta imagen. El jar tiene
# que ser legible por él; los archivos traídos por ADD desde una URL se crean con
# permisos 600, así que el propietario importa.
ADD --chown=1000:1000 \
    https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/${JMX_AGENT_VERSION}/jmx_prometheus_javaagent-${JMX_AGENT_VERSION}.jar \
    /opt/jmx/jmx_prometheus_javaagent.jar

# La configuración del agente se monta desde el compose en /opt/jmx/config.yml,
# para poder cambiar las reglas sin reconstruir la imagen.

EXPOSE 7071
