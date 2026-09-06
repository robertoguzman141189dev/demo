package com.eventlab.jobforge.audit;

import com.eventlab.jobforge.contracts.audit.AuditTopics;
import com.eventlab.jobforge.contracts.audit.JobEvent;
import com.eventlab.jobforge.contracts.audit.TaskState;
import com.eventlab.jobforge.contracts.audit.TaskState.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Convierte la historia en estado: lee {@code trabajos.eventos} y escribe, por
 * cada tarea, cómo está ahora en {@code trabajos.estado}.
 *
 * <p>Es toda la tesis de Kafka en este demo. El log es la verdad; el estado es una
 * conclusión que se deriva de él y que se puede tirar y volver a calcular. Si el
 * topic compactado se perdiera entero, bastaría con volver a leer los eventos
 * desde el principio.
 *
 * <p>No hace falta guardar nada entre mensajes, y eso no es casualidad: los
 * eventos van con la tarea como clave, así que todos los de una misma tarea caen
 * en la misma partición y llegan en orden. Cada evento sabe convertirse en el
 * estado que deja, sin mirar el anterior.
 *
 * <p>Es idempotente por construcción: procesar dos veces el mismo evento escribe
 * el mismo valor bajo la misma clave. Por eso los emisores pueden permitirse
 * duplicar cuando se caen a media escritura doble.
 */
@Component
public class TaskStateProjector {

    private static final Logger log = LoggerFactory.getLogger(TaskStateProjector.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public TaskStateProjector(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = AuditTopics.EVENTS, groupId = "${job-forge.audit.group-id}")
    public void onEvent(JobEvent event) {
        TaskState state = new TaskState(
                event.taskId(),
                event.jobId(),
                statusOf(event.type()),
                event.attempt(),
                event.occurredAt(),
                event.detail());

        // Misma clave que el evento: el estado de una tarea vive siempre en la
        // misma partición del topic compactado y la compactación puede hacer su
        // trabajo, que es quedarse con el último valor de cada clave.
        kafkaTemplate.send(AuditTopics.STATE, event.taskId(), state);

        log.debug("tarea {} -> {}", event.taskId(), state.status());
    }

    private static TaskStatus statusOf(com.eventlab.jobforge.contracts.audit.JobEventType type) {
        return switch (type) {
            case SUBMITTED, REPROCESSED -> TaskStatus.PENDING;
            case COMPLETED, DUPLICATE_DISCARDED -> TaskStatus.COMPLETED;
            case RETRY_SCHEDULED -> TaskStatus.RETRYING;
            case DEAD_LETTERED -> TaskStatus.DEAD;
        };
    }
}
