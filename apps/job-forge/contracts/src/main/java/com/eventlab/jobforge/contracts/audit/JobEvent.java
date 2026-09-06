package com.eventlab.jobforge.contracts.audit;

import java.time.Instant;

/**
 * Un hecho consumado sobre una tarea. Nombre en pasado a propósito: los eventos
 * cuentan lo que ya ocurrió, no lo que se pretende hacer.
 *
 * @param taskId     clave del mensaje en Kafka; decide partición y, con ella, el
 *                   orden en que se lee la historia de esta tarea
 * @param attempt    valor de {@code x-attempt} en el momento del hecho
 * @param detail     texto para humanos: el motivo del fallo, el tramo de espera
 * @param occurredAt cuándo lo observó el emisor, que no es lo mismo que cuándo lo
 *                   escribió Kafka
 * @param source     qué servicio lo emitió, para poder seguir el rastro
 */
public record JobEvent(String jobId,
                       String taskId,
                       JobEventType type,
                       int attempt,
                       String detail,
                       Instant occurredAt,
                       String source) {
}
