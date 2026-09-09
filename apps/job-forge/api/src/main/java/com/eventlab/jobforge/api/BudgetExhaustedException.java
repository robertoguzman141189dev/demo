package com.eventlab.jobforge.api;

import java.time.Duration;

/**
 * Se agotó el presupuesto global de tareas por minuto.
 *
 * <p>Lleva consigo cuánto esperar, porque un 429 sin {@code Retry-After} invita a
 * reintentar de inmediato y convierte el rechazo en un bucle que empeora justo lo
 * que el límite intentaba proteger.
 */
public class BudgetExhaustedException extends RuntimeException {

    private final Duration retryAfter;

    public BudgetExhaustedException(int requested, Duration retryAfter) {
        super("el sistema está aceptando el máximo de trabajo que admite; se pidieron %d tareas"
                .formatted(requested));
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
