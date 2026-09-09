package com.eventlab.jobforge.api;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * El tope global de trabajo aceptado, en tareas por minuto.
 *
 * <p><b>Por qué existe además del rate limiting del ingress.</b> El ingress limita
 * por IP, y eso frena a una persona pulsando el botón. No frena a cien IP
 * distintas pidiendo doscientas tareas cada una: cada petición pasa el límite por
 * IP holgadamente y entre todas llenan las colas. Este es el techo que no depende
 * de quién llame.
 *
 * <p>Es un cubo de fichas: se reponen a ritmo constante y cada tarea aceptada
 * consume una. Cuando el cubo se vacía, se rechaza con 429 y se dice cuánto
 * esperar. No hay cola de espera a propósito: encolar peticiones bajo presión es
 * cambiar un rechazo honesto por una latencia que nadie entiende.
 *
 * <p><b>Limitación que hay que tener escrita:</b> el cubo vive en memoria de cada
 * instancia. Con una réplica —el caso del demo público— el tope es exacto; con
 * tres, el techo real es el triple del configurado. Contarlo de verdad exigiría
 * un contador compartido, y eso es un servicio más que mantener para proteger un
 * demo de datos sintéticos. Se acepta y se documenta en vez de disimularlo.
 */
@Component
public class SubmissionBudget {

    private final long capacity;
    private final double tokensPerNano;

    /**
     * Fichas disponibles en milésimas, no en unidades enteras.
     *
     * <p>Con enteros y un ritmo de 600 por minuto, una petición cada 50 ms
     * repondría cero fichas cada vez —10 tareas por reposición, truncadas a 0— y
     * el cubo no se rellenaría nunca. Trabajar en milésimas evita ese error de
     * redondeo acumulado.
     */
    private final AtomicLong milliTokens;

    private final AtomicLong lastRefillNanos;

    public SubmissionBudget(ApiProperties properties) {
        this.capacity = properties.getGlobalTasksPerMinute();
        this.tokensPerNano = capacity / (double) Duration.ofMinutes(1).toNanos();
        // Arranca lleno: el primer visitante no debe encontrarse la puerta cerrada.
        this.milliTokens = new AtomicLong(capacity * 1000L);
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Intenta reservar presupuesto para un número de tareas.
     *
     * @return las tareas concedidas, que es {@code requested} o cero. No concede
     *         parcialmente: media subida procesada dejaría al visitante creyendo
     *         que se aceptó todo, que es justo lo que el resto del API evita
     */
    public synchronized int tryReserve(int requested) {
        refill();

        long needed = (long) requested * 1000L;
        if (milliTokens.get() < needed) {
            return 0;
        }
        milliTokens.addAndGet(-needed);
        return requested;
    }

    /** Cuánto esperar para que quepa esa petición, para la cabecera Retry-After. */
    public synchronized Duration retryAfter(int requested) {
        refill();

        long missing = (long) requested * 1000L - milliTokens.get();
        if (missing <= 0) {
            return Duration.ZERO;
        }
        long nanos = (long) (missing / 1000.0 / tokensPerNano);
        // Al menos un segundo: un Retry-After de 0 invita a reintentar de inmediato
        // y convierte el rechazo en un bucle.
        return Duration.ofNanos(nanos).plusSeconds(1);
    }

    /** Fichas disponibles ahora, para exponerlo como métrica en los dashboards. */
    public synchronized long available() {
        refill();
        return milliTokens.get() / 1000L;
    }

    private void refill() {
        long now = System.nanoTime();
        long previous = lastRefillNanos.getAndSet(now);
        long elapsed = now - previous;
        if (elapsed <= 0) {
            return;
        }

        long ganadas = (long) (elapsed * tokensPerNano * 1000.0);
        if (ganadas > 0) {
            milliTokens.updateAndGet(actual -> Math.min(capacity * 1000L, actual + ganadas));
        } else {
            // No se ganó ni una milésima: se devuelve el instante anterior para no
            // perder el tiempo transcurrido en llamadas muy seguidas.
            lastRefillNanos.set(previous);
        }
    }
}
