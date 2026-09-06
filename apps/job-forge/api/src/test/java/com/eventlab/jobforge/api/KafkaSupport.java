package com.eventlab.jobforge.api;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Un solo Kafka para todas las pruebas del módulo.
 *
 * <p>A diferencia de RabbitMQ, donde cada clase declara la topología con
 * argumentos distintos y necesita su propio broker, aquí los topics son siempre
 * los mismos. Compartirlo ahorra unos segundos por clase; el precio es que las
 * pruebas ven los eventos que dejaron las anteriores, así que ninguna debe contar
 * mensajes del topic de eventos sin filtrar por su propia tarea.
 */
final class KafkaSupport {

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    static {
        KAFKA.start();
    }

    private KafkaSupport() {
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Un solo broker: no hay a quién replicar.
        registry.add("job-forge.audit.replication-factor", () -> 1);
    }
}
