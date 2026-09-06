package com.eventlab.jobforge.api;

/** Lo que se enseña de una tarea muerta. El cuerpo va recortado. */
public record DeadLetterView(String taskId, String jobId, int attempts, String failureReason, String payload) {
}
