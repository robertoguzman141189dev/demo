package com.eventlab.jobforge.contracts.topology;

/**
 * Nombres de la topología de job-forge.
 *
 * <p>Ningún otro módulo puede escribir el nombre de un exchange, una cola o una
 * routing key como literal. Un nombre mal escrito en un productor no falla al
 * compilar ni al arrancar: el mensaje se publica, no casa con ningún binding y
 * se descarta en silencio. Estas constantes existen para que ese error sea
 * imposible.
 */
public final class JobsTopology {

    /** Punto de entrada de todo trabajo. Direct: la routing key se compara por igualdad. */
    public static final String JOBS_EXCHANGE = "jobs";

    /** Recibe las republicaciones del worker cuando una tarea falla. */
    public static final String RETRY_EXCHANGE = "jobs.retry";

    /** Destino final: intentos agotados, límite de entregas o TTL vencido. */
    public static final String DEAD_LETTER_EXCHANGE = "jobs.dlx";

    /** La única cola con consumidores. */
    public static final String WORK_QUEUE = "jobs.work";

    /** Cola terminal. Se inspecciona y se reprocesa a mano desde el panel. */
    public static final String DEAD_QUEUE = "jobs.dead";

    public static final String WORK_ROUTING_KEY = "work";

    public static final String DEAD_ROUTING_KEY = "dead";

    private JobsTopology() {
    }
}
