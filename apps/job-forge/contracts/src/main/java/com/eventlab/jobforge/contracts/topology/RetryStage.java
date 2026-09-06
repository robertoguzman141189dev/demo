package com.eventlab.jobforge.contracts.topology;

import java.time.Duration;

/**
 * Los tramos del backoff escalonado.
 *
 * <p>Cada tramo es una cola <strong>sin consumidor</strong> con su propio TTL.
 * Nadie las lee jamás: su única función es que el tiempo pase. Cuando el TTL
 * vence, el dead letter exchange devuelve el mensaje a {@code jobs} y el trabajo
 * se vuelve a intentar. Eso es todo el backoff exponencial: tres colas y dos
 * argumentos, sin scheduler, sin base de datos y sin un solo hilo dormido.
 *
 * <p>Son tres colas separadas y no una sola con TTL por mensaje a propósito. Una
 * cola es una fila y RabbitMQ solo comprueba el vencimiento del mensaje que está
 * en la cabeza: con TTL heterogéneos, un mensaje de 5 s atrapado detrás de uno de
 * 2 min espera los 2 minutos completos. Es el bloqueo de cabeza de cola.
 */
public enum RetryStage {

    FIVE_SECONDS("jobs.retry.5s", "retry.5s", Duration.ofSeconds(5)),
    THIRTY_SECONDS("jobs.retry.30s", "retry.30s", Duration.ofSeconds(30)),
    TWO_MINUTES("jobs.retry.2m", "retry.2m", Duration.ofMinutes(2));

    private final String queueName;
    private final String routingKey;
    private final Duration defaultDelay;

    RetryStage(String queueName, String routingKey, Duration defaultDelay) {
        this.queueName = queueName;
        this.routingKey = routingKey;
        this.defaultDelay = defaultDelay;
    }

    public String queueName() {
        return queueName;
    }

    public String routingKey() {
        return routingKey;
    }

    /**
     * Espera de este tramo si nadie la sobrescribe. El valor efectivo lo decide
     * {@link JobTopologyProperties}, para que las pruebas puedan comprimir los
     * tiempos sin tocar la topología.
     */
    public Duration defaultDelay() {
        return defaultDelay;
    }

    /**
     * Tramo que corresponde al intento indicado, contando desde 1.
     *
     * @return {@code null} cuando ya no quedan tramos, es decir, cuando el
     *         trabajo debe ir a la cola de mensajes muertos.
     */
    public static RetryStage forAttempt(int attempt) {
        RetryStage[] stages = values();
        if (attempt < 1 || attempt > stages.length) {
            return null;
        }
        return stages[attempt - 1];
    }

    /** Número de reintentos disponibles antes de la cola terminal. */
    public static int maxAttempts() {
        return values().length;
    }
}
