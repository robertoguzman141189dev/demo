package com.eventlab.jobforge.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "job-forge.worker")
public class WorkerProperties {

    /**
     * Mensajes que el broker entrega a este consumidor sin haber recibido acuse.
     *
     * <p>Es el parámetro que más engaña. Subirlo mejora el throughput porque
     * elimina el viaje de ida y vuelta entre tarea y tarea, pero reparte peor: las
     * tareas que un worker tiene retenidas ya tienen dueño y ningún compañero
     * ocioso puede tomarlas. Con tareas largas y desiguales —las de job-forge— el
     * resultado de subirlo es un worker saturado y el resto mirando.
     *
     * <p>Además rompe las prioridades: si el worker ya tiene retenidas veinte
     * tareas normales, una urgente que llegue después espera a que se vacíe ese
     * buffer local, donde el orden ya está decidido.
     */
    private int prefetch = 1;

    /** Consumidores concurrentes dentro de un mismo proceso. */
    private int concurrency = 1;

    /** Duración base del trabajo simulado. */
    private Duration taskDuration = Duration.ofSeconds(2);

    /**
     * Variación aleatoria sobre la duración, hacia arriba. Con tareas de duración
     * idéntica el reparto parece perfecto siempre y el efecto del prefetch no se
     * ve; la desigualdad es lo que hace visible el problema.
     */
    private Duration taskJitter = Duration.ofSeconds(3);

    /**
     * Cuánto recuerda el worker que ya procesó una tarea. Pasado ese tiempo, un
     * duplicado tardío se volvería a procesar. Debe cubrir con holgura el reintento
     * más largo, o la deduplicación no sirve justo cuando hace falta.
     */
    private Duration deduplicationWindow = Duration.ofMinutes(30);

    /** Tope de tareas recordadas. Es un caché en memoria, no una base de datos. */
    private int deduplicationCapacity = 10_000;

    /**
     * Margen para terminar la tarea en curso cuando llega la señal de apagado.
     * Debe ser menor que el {@code terminationGracePeriodSeconds} del pod y mayor
     * que la tarea más lenta, o cada despliegue genera reprocesos.
     */
    private Duration shutdownTimeout = Duration.ofSeconds(30);

    /**
     * Plazo para reconstruir el estado desde el topic compactado al arrancar.
     * Agotado, el worker falla y el pod se reinicia: es preferible a quedarse
     * colgado esperando a Kafka, y muy preferible a consumir sin saber qué tareas
     * ya se hicieron.
     */
    private Duration stateRebuildTimeout = Duration.ofSeconds(60);

    public int getPrefetch() {
        return prefetch;
    }

    public void setPrefetch(int prefetch) {
        this.prefetch = prefetch;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public Duration getTaskDuration() {
        return taskDuration;
    }

    public void setTaskDuration(Duration taskDuration) {
        this.taskDuration = taskDuration;
    }

    public Duration getTaskJitter() {
        return taskJitter;
    }

    public void setTaskJitter(Duration taskJitter) {
        this.taskJitter = taskJitter;
    }

    public Duration getDeduplicationWindow() {
        return deduplicationWindow;
    }

    public void setDeduplicationWindow(Duration deduplicationWindow) {
        this.deduplicationWindow = deduplicationWindow;
    }

    public int getDeduplicationCapacity() {
        return deduplicationCapacity;
    }

    public void setDeduplicationCapacity(int deduplicationCapacity) {
        this.deduplicationCapacity = deduplicationCapacity;
    }

    public Duration getStateRebuildTimeout() {
        return stateRebuildTimeout;
    }

    public void setStateRebuildTimeout(Duration stateRebuildTimeout) {
        this.stateRebuildTimeout = stateRebuildTimeout;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }
}
