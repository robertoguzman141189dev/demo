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
     * <p>Esto es un tope por petición, no un límite de uso. El límite de uso son
     * {@code globalTasksPerMinute} aquí abajo y el rate limiting por IP del
     * ingress.
     */
    private int maxTasksPerJob = 200;

    /**
     * Techo global de tareas aceptadas por minuto, sumando a todo el mundo.
     *
     * <p>Es la defensa que el rate limiting por IP del ingress no puede dar: cien
     * IP distintas pidiendo doscientas tareas cada una pasan holgadamente
     * cualquier límite por IP y entre todas llenan las colas.
     *
     * <p>600 por minuto son diez por segundo: de sobra para que alguien juegue con
     * el panel —cada pulsación son 10 o 20 tareas— y muy por debajo de lo que
     * cuesta llenar un nodo de 4 GiB. Ver {@link SubmissionBudget} para cómo se
     * cuenta y qué limitación tiene con más de una réplica.
     */
    private int globalTasksPerMinute = 600;

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

    public int getGlobalTasksPerMinute() {
        return globalTasksPerMinute;
    }

    public void setGlobalTasksPerMinute(int globalTasksPerMinute) {
        this.globalTasksPerMinute = globalTasksPerMinute;
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
