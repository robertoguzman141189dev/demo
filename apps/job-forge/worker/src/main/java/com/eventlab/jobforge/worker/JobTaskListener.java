package com.eventlab.jobforge.worker;

import com.eventlab.jobforge.contracts.audit.JobEventPublisher;
import com.eventlab.jobforge.contracts.audit.JobEventType;
import com.eventlab.jobforge.contracts.message.JobHeaders;
import com.eventlab.jobforge.contracts.message.JobTask;
import com.eventlab.jobforge.contracts.publish.MessageNotConfirmedException;
import com.eventlab.jobforge.contracts.retry.RetryTarget;
import com.eventlab.jobforge.contracts.topology.JobsTopology;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * El consumidor. Aquí se juntan todas las piezas de F1 y F2.
 *
 * <p>Acusa a mano, nunca automáticamente: con acuse automático el broker da por
 * entregado el mensaje en cuanto lo escribe en el socket, y un worker que muera
 * procesando se lleva el trabajo con él sin que nadie se entere.
 */
@Component
public class JobTaskListener {

    private static final Logger log = LoggerFactory.getLogger(JobTaskListener.class);

    private final TaskProcessor processor;
    private final FailureInjector failureInjector;
    private final ProcessedTaskRegistry registry;
    private final TaskRetryPublisher retryPublisher;
    private final JobEventPublisher events;

    private final Counter completed;
    private final Counter duplicates;
    private final Counter retried;
    private final Counter dead;

    public JobTaskListener(TaskProcessor processor,
                           FailureInjector failureInjector,
                           ProcessedTaskRegistry registry,
                           TaskRetryPublisher retryPublisher,
                           JobEventPublisher events,
                           MeterRegistry meters) {
        this.processor = processor;
        this.failureInjector = failureInjector;
        this.registry = registry;
        this.retryPublisher = retryPublisher;
        this.events = events;
        this.completed = Counter.builder("jobforge.tasks").tag("outcome", "completed").register(meters);
        this.duplicates = Counter.builder("jobforge.tasks").tag("outcome", "duplicate").register(meters);
        this.retried = Counter.builder("jobforge.tasks").tag("outcome", "retried").register(meters);
        this.dead = Counter.builder("jobforge.tasks").tag("outcome", "dead").register(meters);
    }

    @RabbitListener(queues = JobsTopology.WORK_QUEUE, containerFactory = "workerListenerContainerFactory")
    public void onTask(JobTask task, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        int attempt = intHeader(message, JobHeaders.ATTEMPT, 0);
        int failProbability = intHeader(message, JobHeaders.FAIL_PROBABILITY, 0);

        // Preguntar antes de trabajar, no después: el trabajo es lo caro.
        if (registry.alreadyProcessed(task.taskId())) {
            log.info("tarea {} ya procesada, se descarta el duplicado", task.taskId());
            events.publish(task.jobId(), task.taskId(), JobEventType.DUPLICATE_DISCARDED, attempt,
                    "descartada sin trabajar");
            duplicates.increment();
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            Duration took = processor.execute(task);

            if (failureInjector.shouldFail(failProbability)) {
                throw new TaskFailedException(
                        "fallo inyectado con probabilidad %d%%".formatted(failProbability));
            }

            // Primero el hecho a salvo en Kafka, después soltar el mensaje. Es el
            // mismo criterio del ADR 004 aplicado a la escritura doble: si el
            // proceso muere en medio, el evento queda duplicado —inofensivo, la
            // proyección es idempotente por clave— en vez de perderse.
            events.publish(task.jobId(), task.taskId(), JobEventType.COMPLETED, attempt,
                    "completada en %d ms".formatted(took.toMillis()));
            registry.markProcessed(task.taskId());
            channel.basicAck(deliveryTag, false);
            completed.increment();
            log.info("tarea {} completada en {} ms (intento {})", task.taskId(), took.toMillis(), attempt);

        } catch (InterruptedException e) {
            // Apagado a media tarea. No se acusa ni se rechaza: al cerrarse la
            // conexión, el broker devuelve la tarea a la cola y otro worker la
            // recoge. Restaurar la marca de interrupción es lo que permite que el
            // resto del apagado siga su curso.
            Thread.currentThread().interrupt();
            log.warn("apagado a media tarea {}; queda sin acusar para que la reentreguen", task.taskId());

        } catch (TaskFailedException e) {
            handleFailure(task, message, channel, deliveryTag, attempt, e.getMessage());
        }
    }

    private void handleFailure(JobTask task, Message message, Channel channel,
                               long deliveryTag, int attempt, String reason) throws IOException {
        try {
            RetryTarget target = retryPublisher.republish(message, task, attempt + 1, reason);

            events.publish(task.jobId(), task.taskId(),
                    target instanceof RetryTarget.Dead ? JobEventType.DEAD_LETTERED : JobEventType.RETRY_SCHEDULED,
                    attempt + 1, reason);

            // El orden es el diseño: publicar, confirmar y solo entonces acusar. Si
            // se acusara antes y la publicación se perdiera, la tarea ya no estaría
            // en la cola de trabajo y nunca habría llegado a la de espera.
            channel.basicAck(deliveryTag, false);

            switch (target) {
                case RetryTarget.Delayed delayed -> {
                    retried.increment();
                    log.info("tarea {} falló en el intento {}: espera en {}",
                            task.taskId(), attempt + 1, delayed.stage().queueName());
                }
                case RetryTarget.Dead ignored -> {
                    dead.increment();
                    log.warn("tarea {} agotó los intentos y va a la cola muerta: {}",
                            task.taskId(), reason);
                }
            }

        } catch (MessageNotConfirmedException e) {
            // No se pudo poner a salvo: la tarea sigue siendo nuestra. Se rechaza
            // reencolando, contando con que el problema del broker sea pasajero.
            // Si no lo fuera, esta tarea volvería en bucle — pero si publicar está
            // roto, todo lo demás también lo está.
            log.error("no se pudo republicar la tarea {}, se devuelve a la cola", task.taskId(), e);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private static int intHeader(Message message, String name, int fallback) {
        Object value = message.getMessageProperties().getHeader(name);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
