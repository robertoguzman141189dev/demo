package com.eventlab.jobforge.contracts.audit;

import java.time.Instant;

/**
 * El estado actual de una tarea, tal y como vive en el topic compactado.
 *
 * <p>Es una proyección: no se escribe a mano, se deriva del log de eventos. Si se
 * borrara el topic entero, se podría reconstruir volviendo a leer
 * {@link AuditTopics#EVENTS} desde el principio.
 */
public record TaskState(String taskId,
                        String jobId,
                        TaskStatus status,
                        int attempts,
                        Instant updatedAt,
                        String detail) {

    public enum TaskStatus {
        PENDING,
        COMPLETED,
        RETRYING,
        DEAD
    }
}
