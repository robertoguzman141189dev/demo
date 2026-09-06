package com.eventlab.jobforge.contracts.message;

/**
 * Headers del protocolo de job-forge.
 *
 * <p>El contador de intentos vive aquí y nunca en el cuerpo del mensaje. El
 * cuerpo es el trabajo del usuario: si el número de intentos viviera dentro,
 * cada reintento modificaría la carga útil y dejaría de ser posible comparar dos
 * entregas del mismo trabajo, que es justo lo que hace falta para ser
 * idempotente.
 */
public final class JobHeaders {

    /**
     * Intentos ya realizados sobre esta tarea. Lo incrementa el worker al
     * republicar, nunca el broker.
     *
     * <p>No se debe confundir con {@link #X_DEATH}, que sí pone el broker. Ese
     * cuenta cuántas veces el mensaje ha sido dead-lettereado; este cuenta
     * cuántas veces alguien decidió que había fallado. Los dos números divergen
     * en cuanto un worker muere sin acusar: el trabajo se reentrega, el broker no
     * lo considera muerto y el contador de decisiones no avanza.
     */
    public static final String ATTEMPT = "x-attempt";

    /** Trabajo al que pertenece la tarea. Base de la idempotencia del consumidor. */
    public static final String JOB_ID = "x-job-id";

    /** Identidad de la unidad de trabajo. Único por tarea, estable entre reintentos. */
    public static final String TASK_ID = "x-task-id";

    /** Motivo del último fallo. Solo para inspección humana en la cola muerta. */
    public static final String FAILURE_REASON = "x-failure-reason";

    /**
     * Probabilidad de fallo, de 0 a 100, que el worker debe aplicar a esta tarea.
     *
     * <p>La palanca del panel viaja en el mensaje y no en la configuración del
     * worker a propósito: con varias réplicas detrás de un Service, un endpoint de
     * control solo alcanzaría a una de ellas y el demo mentiría en cuanto se
     * escala. Estampada en la tarea, la condición del experimento es la misma para
     * todas las réplicas y queda escrita en el propio mensaje.
     */
    public static final String FAIL_PROBABILITY = "x-fail-probability";

    /**
     * Lo escribe RabbitMQ, no nosotros. Registra el historial de dead-lettering:
     * cola de origen, motivo y cuenta. Se lee, nunca se escribe.
     */
    public static final String X_DEATH = "x-death";

    private JobHeaders() {
    }
}
