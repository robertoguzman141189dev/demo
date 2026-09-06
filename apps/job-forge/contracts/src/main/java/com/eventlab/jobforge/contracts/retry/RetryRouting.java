package com.eventlab.jobforge.contracts.retry;

import com.eventlab.jobforge.contracts.topology.RetryStage;

/**
 * Decide el destino de una tarea fallida. Función pura, sin Spring y sin broker:
 * el worker (F2) la usa para saber a dónde republicar.
 *
 * <p><strong>Por qué republicar y no simplemente rechazar el mensaje.</strong>
 * Cuando RabbitMQ dead-letterea, el destino sale de los argumentos de la cola,
 * que son estáticos, y los headers no se pueden modificar por el camino. Un
 * {@code nack} siempre lleva al mismo sitio con el mismo TTL: no hay
 * escalonamiento posible por dead-lettering. Para que el segundo intento espere
 * más que el primero hace falta que alguien elija el tramo, y ese alguien es el
 * consumidor.
 *
 * <p>El orden importa: publicar, esperar la confirmación del broker y solo
 * entonces acusar el mensaje original. Al revés se pierde el trabajo si la
 * publicación se rechaza.
 */
public final class RetryRouting {

    private RetryRouting() {
    }

    /**
     * @param attempt número de intentos ya realizados, incluido el que acaba de
     *                fallar. Un mensaje recién publicado llega con
     *                {@code x-attempt = 0}, así que tras el primer fallo vale 1.
     */
    public static RetryTarget targetFor(int attempt, String failureReason) {
        if (attempt < 1) {
            throw new IllegalArgumentException(
                    "attempt debe ser >= 1; un fallo siempre es al menos el primer intento, recibido: " + attempt);
        }
        RetryStage stage = RetryStage.forAttempt(attempt);
        if (stage == null) {
            return new RetryTarget.Dead(
                    "intentos agotados tras %d de %d: %s".formatted(attempt, RetryStage.maxAttempts(), failureReason));
        }
        return new RetryTarget.Delayed(stage);
    }
}
