package com.eventlab.jobforge.contracts.audit;

/** Qué le puede pasar a una tarea. La proyección traduce esto a estado. */
public enum JobEventType {

    /** El API la aceptó y la publicó. */
    SUBMITTED,

    /** Un worker la terminó y la acusó. */
    COMPLETED,

    /** Falló y se republicó a un tramo de espera. */
    RETRY_SCHEDULED,

    /** Agotó los intentos: está en la cola muerta, esperando decisión humana. */
    DEAD_LETTERED,

    /** Llegó por segunda vez y se descartó sin trabajar. */
    DUPLICATE_DISCARDED,

    /** Alguien la sacó a mano de la cola muerta y la devolvió al circuito. */
    REPROCESSED
}
