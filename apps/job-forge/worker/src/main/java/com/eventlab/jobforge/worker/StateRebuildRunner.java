package com.eventlab.jobforge.worker;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * El arranque del worker, en el orden correcto: primero saber, después trabajar.
 *
 * <p>Los consumidores de RabbitMQ arrancan parados a propósito
 * ({@code autoStartup=false} en la fábrica). Este componente reconstruye el
 * estado desde el topic compactado y solo entonces los pone en marcha. Si
 * consumiera antes, no reconocería los duplicados y repetiría trabajo ya hecho:
 * exactamente la tanda de reprocesos que produce cada despliegue cuando la
 * readiness solo mira si el puerto responde.
 *
 * <p>Este componente es también el indicador de readiness. Mientras no termine,
 * el pod no está listo, y Kubernetes no le manda nada. El puerto contesta desde el
 * primer segundo; el worker sirve para algo bastante después.
 */
@Component
public class StateRebuildRunner implements ApplicationRunner, HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(StateRebuildRunner.class);

    private final TaskStateLoader loader;
    private final ProcessedTaskRegistry registry;
    private final RabbitListenerEndpointRegistry listeners;
    private final Duration timeout;

    private volatile boolean ready;
    private volatile int recovered;

    public StateRebuildRunner(TaskStateLoader loader,
                              ProcessedTaskRegistry registry,
                              RabbitListenerEndpointRegistry listeners,
                              WorkerProperties properties) {
        this.loader = loader;
        this.registry = registry;
        this.listeners = listeners;
        this.timeout = properties.getStateRebuildTimeout();
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("reconstruyendo estado antes de consumir; plazo máximo {}", timeout);
        recovered = loader.loadCompletedInto(registry, timeout);
        ready = true;

        listeners.getListenerContainers().forEach(MessageListenerContainer::start);
        log.info("worker listo: {} tareas completadas recuperadas, consumidores en marcha", recovered);
    }

    @Override
    public Health health() {
        return ready
                ? Health.up().withDetail("tareasRecuperadas", recovered).build()
                : Health.outOfService().withDetail("estado", "reconstruyendo desde Kafka").build();
    }
}
