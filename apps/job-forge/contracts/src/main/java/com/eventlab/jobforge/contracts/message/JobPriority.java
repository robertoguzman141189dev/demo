package com.eventlab.jobforge.contracts.message;

/**
 * Prioridad de una tarea.
 *
 * <p>Las colas quorum <strong>no aceptan</strong> el argumento
 * {@code x-max-priority}: el broker rechaza la declaración con
 * {@code invalid arg 'x-max-priority' ... of queue type rabbit_quorum_queue}. Ese
 * argumento pertenece a las colas clásicas, que viven en un solo nodo y no dan
 * la garantía de entrega sobre la que se apoya job-forge.
 *
 * <p>Lo que sí hacen las colas quorum de RabbitMQ 4.x es respetar la propiedad
 * {@code priority} del mensaje, con dos niveles efectivos en lugar de cinco:
 * a partir de 5 el mensaje es de prioridad alta y adelanta a los normales. Ver
 * ADR 003.
 */
public enum JobPriority {

    NORMAL(0),
    HIGH(5);

    private final int amqpPriority;

    JobPriority(int amqpPriority) {
        this.amqpPriority = amqpPriority;
    }

    /** Valor que va en la propiedad {@code priority} del mensaje AMQP. */
    public int amqpPriority() {
        return amqpPriority;
    }
}
