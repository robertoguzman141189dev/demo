package com.eventlab.jobforge.contracts.publish;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "job-forge.publish")
public class PublisherProperties {

    /**
     * Cuánto se espera la confirmación del broker antes de darla por perdida.
     *
     * <p>Demasiado corto y un pico de latencia se interpreta como fallo: el worker
     * no acusa, el mensaje se reentrega y la tarea se duplica. Demasiado largo y
     * un broker enfermo deja hilos del worker bloqueados sin hacer nada.
     */
    private Duration confirmTimeout = Duration.ofSeconds(5);

    public Duration getConfirmTimeout() {
        return confirmTimeout;
    }

    public void setConfirmTimeout(Duration confirmTimeout) {
        this.confirmTimeout = confirmTimeout;
    }
}
