package com.eventlab.jobforge.contracts.audit;

/**
 * Los dos topics de Kafka de job-forge, y la razón de que sean dos.
 *
 * <p>Kafka está aquí en su papel correcto: log de auditoría. No es una cola de
 * trabajo —eso lo hace RabbitMQ, que da acuse por unidad, TTL y dead-lettering—
 * sino el registro de qué ha pasado y el estado que se deriva de él.
 */
public final class AuditTopics {

    /**
     * La historia: un hecho por cada cosa que le ocurre a una tarea. Política
     * {@code delete} con retención por tiempo, porque una historia caduca.
     *
     * <p>La clave es el id de la tarea, no por capricho: Kafka reparte por hash de
     * la clave y solo garantiza el orden <em>dentro</em> de una partición. Con esta
     * clave, todos los hechos de una tarea caen en la misma partición y su historia
     * se lee en el orden en que ocurrió.
     */
    public static final String EVENTS = "trabajos.eventos";

    /**
     * El estado actual de cada tarea. Política {@code compact}: Kafka garantiza
     * conservar el último valor de cada clave y va borrando los anteriores. Leer
     * este topic entero desde el principio devuelve el estado de todo el sistema,
     * sin base de datos.
     *
     * <p>De aquí reconstruye el worker, al arrancar, qué tareas ya completó.
     */
    public static final String STATE = "trabajos.estado";

    private AuditTopics() {
    }
}
