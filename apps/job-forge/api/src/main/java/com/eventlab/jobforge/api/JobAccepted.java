package com.eventlab.jobforge.api;

import com.eventlab.jobforge.contracts.message.JobPriority;

/**
 * Lo que se devuelve al aceptar un trabajo. Todas las tareas están confirmadas
 * por el broker: si esta respuesta llega, el trabajo existe.
 */
public record JobAccepted(String jobId, int tasks, JobPriority priority, int failProbability) {
}
