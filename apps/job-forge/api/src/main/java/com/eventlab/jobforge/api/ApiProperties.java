package com.eventlab.jobforge.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "job-forge.api")
public class ApiProperties {

    /**
     * Tope de tareas que puede generar una sola subida.
     *
     * <p>El panel es público y anónimo: un archivo de cien mil líneas es un vector
     * de gasto, no un caso de uso. Se rechaza en vez de recortar en silencio,
     * porque recortar dejaría al visitante creyendo que procesó todo.
     *
     * <p>Esto es un tope por petición, no un límite de uso. El rate limiting real
     * —por sesión y global, en el ingress— es de F7.
     */
    private int maxTasksPerJob = 200;

    /** Longitud máxima del fragmento que viaja en cada tarea. */
    private int maxPayloadLength = 512;

    /** Tope de tareas que un solo reproceso puede sacar de la cola muerta. */
    private int maxReprocessBatch = 50;

    public int getMaxTasksPerJob() {
        return maxTasksPerJob;
    }

    public void setMaxTasksPerJob(int maxTasksPerJob) {
        this.maxTasksPerJob = maxTasksPerJob;
    }

    public int getMaxPayloadLength() {
        return maxPayloadLength;
    }

    public void setMaxPayloadLength(int maxPayloadLength) {
        this.maxPayloadLength = maxPayloadLength;
    }

    public int getMaxReprocessBatch() {
        return maxReprocessBatch;
    }

    public void setMaxReprocessBatch(int maxReprocessBatch) {
        this.maxReprocessBatch = maxReprocessBatch;
    }
}
