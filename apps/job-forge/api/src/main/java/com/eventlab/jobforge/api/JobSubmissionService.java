package com.eventlab.jobforge.api;

import com.eventlab.jobforge.contracts.audit.JobEventPublisher;
import com.eventlab.jobforge.contracts.audit.JobEventType;
import com.eventlab.jobforge.contracts.message.JobHeaders;
import com.eventlab.jobforge.contracts.message.JobPriority;
import com.eventlab.jobforge.contracts.message.JobTask;
import com.eventlab.jobforge.contracts.publish.ConfirmedPublisher;
import com.eventlab.jobforge.contracts.topology.JobsTopology;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Descompone lo que sube el visitante en tareas y las publica.
 *
 * <p>El archivo no se guarda en ninguna parte: se lee, se parte y se descarta.
 * Cumplir la promesa de "los archivos se borran a los 30 minutos" es más fácil
 * cuando no hay archivo que borrar. Lo que sobrevive son fragmentos de texto
 * dentro de mensajes, sujetos a la retención de las colas.
 */
@Service
@EnableConfigurationProperties(ApiProperties.class)
public class JobSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(JobSubmissionService.class);

    private final ConfirmedPublisher publisher;
    private final MessageConverter converter;
    private final ApiProperties properties;
    private final JobEventPublisher events;
    private final SubmissionBudget budget;

    public JobSubmissionService(ConfirmedPublisher publisher,
                                MessageConverter converter,
                                ApiProperties properties,
                                JobEventPublisher events,
                                SubmissionBudget budget) {
        this.publisher = publisher;
        this.converter = converter;
        this.properties = properties;
        this.events = events;
        this.budget = budget;
    }

    public JobAccepted submit(String content, JobPriority priority, int failProbability) {
        List<String> fragments = split(content);
        if (fragments.isEmpty()) {
            throw new InvalidSubmissionException("el archivo no tiene ninguna línea con contenido");
        }
        if (fragments.size() > properties.getMaxTasksPerJob()) {
            throw new InvalidSubmissionException(
                    "el archivo genera %d tareas y el máximo por subida es %d"
                            .formatted(fragments.size(), properties.getMaxTasksPerJob()));
        }

        // El tope global va aquí y no en el controlador, por dos motivos: cubre las
        // dos vías de entrada —subida y generador sintético— con un solo control, y
        // reserva el número EXACTO de tareas, porque a esta altura ya están
        // contadas. Reservar en el controlador obligaría a duplicar la lógica de
        // partido en líneas, y dos implementaciones del mismo recuento acaban
        // divergiendo.
        //
        // Se reserva antes de publicar nada. Publicar y luego descubrir que no
        // había presupuesto dejaría trabajo a medias en la cola.
        if (budget.tryReserve(fragments.size()) == 0) {
            throw new BudgetExhaustedException(fragments.size(), budget.retryAfter(fragments.size()));
        }

        String jobId = UUID.randomUUID().toString();
        int published = 0;
        for (int index = 0; index < fragments.size(); index++) {
            JobTask task = new JobTask(jobId, UUID.randomUUID().toString(), index, fragments.get(index));
            publish(task, priority, failProbability);
            published++;
        }

        log.info("trabajo {} aceptado: {} tareas, prioridad {}, fallo al {}%",
                jobId, published, priority, failProbability);
        return new JobAccepted(jobId, published, priority, failProbability);
    }

    private void publish(JobTask task, JobPriority priority, int failProbability) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        // Las colas quorum no aceptan x-max-priority, pero sí respetan esta
        // propiedad, con dos niveles efectivos: normal y alta (ADR 003).
        messageProperties.setPriority(priority.amqpPriority());
        messageProperties.setHeader(JobHeaders.JOB_ID, task.jobId());
        messageProperties.setHeader(JobHeaders.TASK_ID, task.taskId());
        messageProperties.setHeader(JobHeaders.ATTEMPT, 0);
        messageProperties.setHeader(JobHeaders.FAIL_PROBABILITY, failProbability);

        Message message = converter.toMessage(task, messageProperties);
        // Si esto lanza, el trabajo se corta a medias y el visitante recibe un
        // error. Es preferible a responder "aceptado" por tareas que el broker
        // nunca llegó a tener.
        publisher.publish(JobsTopology.JOBS_EXCHANGE, JobsTopology.WORK_ROUTING_KEY,
                message, task.taskId());
        events.publish(task.jobId(), task.taskId(), JobEventType.SUBMITTED, 0,
                "aceptada con prioridad %s y fallo al %d%%".formatted(priority, failProbability));
    }

    private List<String> split(String content) {
        List<String> fragments = new ArrayList<>();
        for (String line : content.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            fragments.add(trimmed.length() > properties.getMaxPayloadLength()
                    ? trimmed.substring(0, properties.getMaxPayloadLength())
                    : trimmed);
        }
        return fragments;
    }
}
