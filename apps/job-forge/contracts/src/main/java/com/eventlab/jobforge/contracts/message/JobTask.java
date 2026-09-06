package com.eventlab.jobforge.contracts.message;

/**
 * Una unidad de trabajo. El cuerpo del mensaje, sin metadatos de entrega: esos
 * viven en los headers y por eso pueden cambiar entre intentos sin alterar la
 * carga útil.
 *
 * @param jobId  trabajo al que pertenece; un archivo subido produce muchas tareas
 *               con el mismo jobId
 * @param taskId identidad estable de esta unidad. Es la clave de deduplicación:
 *               no se regenera nunca, ni al republicar ni al reprocesar desde la
 *               cola muerta. Una clave que cambiara entre intentos haría que la
 *               deduplicación no deduplicara nada, y el sistema parecería
 *               correcto hasta el día que no lo fuera
 * @param index  posición dentro del trabajo, para poder ordenar la vista
 * @param payload contenido de la tarea. Datos sintéticos: un fragmento del
 *               archivo que subió el visitante
 */
public record JobTask(String jobId, String taskId, int index, String payload) {
}
