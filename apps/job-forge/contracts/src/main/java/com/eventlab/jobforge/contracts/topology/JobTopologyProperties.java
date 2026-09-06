package com.eventlab.jobforge.contracts.topology;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parámetros de la topología. Existen para que las pruebas puedan comprimir los
 * tiempos de espera sin cambiar ni una línea de la topología: lo que se prueba
 * es el mecanismo, y el mecanismo no depende de si el tramo dura 5 segundos o
 * 500 milisegundos.
 *
 * <p>Ejemplo:
 * <pre>
 * job-forge:
 *   topology:
 *     work-ttl: 15m
 *     delivery-limit: 5
 *     retry-delays:
 *       five-seconds: 5s
 *       thirty-seconds: 30s
 *       two-minutes: 2m
 * </pre>
 */
@ConfigurationProperties(prefix = "job-forge.topology")
public class JobTopologyProperties {

    /**
     * Tiempo máximo que una tarea puede esperar en {@code jobs.work} sin que
     * nadie la consuma. Al vencer va a la cola muerta, no a reintentos: que
     * caduque aquí no significa que haya fallado, significa que no hay quien la
     * procese, y volver a encolarla solo repetiría el problema (ADR 003).
     */
    private Duration workTtl = Duration.ofMinutes(15);

    /**
     * Entregas máximas de un mismo mensaje antes de que la cola quorum lo
     * dead-letteree por su cuenta. Es la red que el contador {@code x-attempt} no
     * puede tender: cubre al worker que muere <em>antes</em> de decidir nada, y
     * por tanto antes de incrementar nada. Sin este límite, un mensaje que hace
     * caer al worker lo hace caer para siempre.
     */
    private int deliveryLimit = 5;

    /** Espera efectiva de cada tramo. Lo que falte toma el valor por defecto del tramo. */
    private Map<RetryStage, Duration> retryDelays = new EnumMap<>(RetryStage.class);

    public Duration delayOf(RetryStage stage) {
        return retryDelays.getOrDefault(stage, stage.defaultDelay());
    }

    public Duration getWorkTtl() {
        return workTtl;
    }

    public void setWorkTtl(Duration workTtl) {
        this.workTtl = workTtl;
    }

    public int getDeliveryLimit() {
        return deliveryLimit;
    }

    public void setDeliveryLimit(int deliveryLimit) {
        this.deliveryLimit = deliveryLimit;
    }

    public Map<RetryStage, Duration> getRetryDelays() {
        return retryDelays;
    }

    public void setRetryDelays(Map<RetryStage, Duration> retryDelays) {
        this.retryDelays = retryDelays;
    }
}
