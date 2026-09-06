package com.eventlab.jobforge.contracts.retry;

import com.eventlab.jobforge.contracts.topology.JobsTopology;
import com.eventlab.jobforge.contracts.topology.RetryStage;

/**
 * A dónde va una tarea que acaba de fallar.
 *
 * <p>Solo hay dos destinos posibles, y el tipo lo hace explícito: o espera en un
 * tramo del backoff, o se acabó.
 */
public sealed interface RetryTarget {

    String exchange();

    String routingKey();

    /** Espera en un tramo del backoff y vuelve sola a {@code jobs} al vencer el TTL. */
    record Delayed(RetryStage stage) implements RetryTarget {

        @Override
        public String exchange() {
            return JobsTopology.RETRY_EXCHANGE;
        }

        @Override
        public String routingKey() {
            return stage.routingKey();
        }
    }

    /** Intentos agotados: a la cola muerta, para inspección y reproceso manual. */
    record Dead(String reason) implements RetryTarget {

        @Override
        public String exchange() {
            return JobsTopology.DEAD_LETTER_EXCHANGE;
        }

        @Override
        public String routingKey() {
            return JobsTopology.DEAD_ROUTING_KEY;
        }
    }
}
