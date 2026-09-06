package com.eventlab.jobforge.contracts.audit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Emite hechos al log de auditoría y <strong>espera</strong> a que Kafka los
 * acepte.
 *
 * <p>La espera es la decisión, no un descuido. El emisor tiene entre manos dos
 * sistemas distintos —acusar en RabbitMQ y escribir en Kafka— y no hay forma de
 * hacer ambas cosas atómicamente sin transacciones distribuidas. Se elige de qué
 * lado fallar: primero se pone el hecho a salvo y después se suelta el mensaje.
 * Si el proceso muere en medio, el evento quedará duplicado, y eso es inofensivo
 * porque la proyección es idempotente por clave: el último valor gana. Al revés,
 * el fallo sería un agujero silencioso en la auditoría justo en el caso
 * interesante, que es la caída.
 *
 * <p>La clave del mensaje es el id de la tarea. De ahí sale que su historia viva
 * entera en una partición y se lea en orden.
 */
@Component
public class JobEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Clock clock;
    private final String source;
    private final Duration timeout;

    // Dos constructores: sin marcar uno, Spring exige uno sin argumentos.
    @Autowired
    public JobEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                             @Value("${spring.application.name:job-forge}") String source,
                             @Value("${job-forge.audit.publish-timeout:5s}") Duration timeout) {
        this(kafkaTemplate, Clock.systemUTC(), source, timeout);
    }

    JobEventPublisher(KafkaTemplate<String, Object> kafkaTemplate, Clock clock,
                      String source, Duration timeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.source = source;
        this.timeout = timeout;
    }

    public void publish(String jobId, String taskId, JobEventType type, int attempt, String detail) {
        JobEvent event = new JobEvent(jobId, taskId, type, attempt, detail, clock.instant(), source);
        try {
            kafkaTemplate.send(AuditTopics.EVENTS, taskId, event)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuditNotRecordedException("interrumpido al registrar %s de %s".formatted(type, taskId), e);
        } catch (Exception e) {
            throw new AuditNotRecordedException("no se pudo registrar %s de %s".formatted(type, taskId), e);
        }
    }
}
