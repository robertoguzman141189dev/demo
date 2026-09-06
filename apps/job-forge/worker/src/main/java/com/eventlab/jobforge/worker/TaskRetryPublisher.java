package com.eventlab.jobforge.worker;

import com.eventlab.jobforge.contracts.message.JobHeaders;
import com.eventlab.jobforge.contracts.message.JobTask;
import com.eventlab.jobforge.contracts.publish.ConfirmedPublisher;
import com.eventlab.jobforge.contracts.retry.RetryRouting;
import com.eventlab.jobforge.contracts.retry.RetryTarget;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;

/**
 * Reconstruye la tarea fallida y la publica en el tramo que le toca.
 *
 * <p>Se construye un mensaje nuevo en lugar de reenviar el original porque los
 * headers de un mensaje recibido no son editables, y porque hay headers que
 * <strong>no</strong> queremos arrastrar: {@code x-death} y
 * {@code x-acquired-count} los escribe el broker sobre su propia contabilidad, y
 * copiarlos a un mensaje nuevo produciría un historial inventado. Lo único que
 * viaja es el cuerpo intacto y nuestro propio protocolo.
 */
@Component
public class TaskRetryPublisher {

    private final ConfirmedPublisher publisher;

    public TaskRetryPublisher(ConfirmedPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Publica y espera confirmación. Si vuelve sin excepción, el trabajo ya está a
     * salvo en el broker y quien llame puede acusar el mensaje original — en ese
     * orden y no al revés.
     */
    public RetryTarget republish(Message original, JobTask task, int nextAttempt, String reason) {
        RetryTarget target = RetryRouting.targetFor(nextAttempt, reason);
        MessageProperties source = original.getMessageProperties();

        MessageProperties properties = new MessageProperties();
        properties.setContentType(source.getContentType());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setPriority(source.getPriority() == null ? 0 : source.getPriority());
        properties.setHeader(JobHeaders.JOB_ID, task.jobId());
        properties.setHeader(JobHeaders.TASK_ID, task.taskId());
        properties.setHeader(JobHeaders.ATTEMPT, nextAttempt);
        properties.setHeader(JobHeaders.FAILURE_REASON, reason);
        Object failProbability = source.getHeader(JobHeaders.FAIL_PROBABILITY);
        if (failProbability != null) {
            // La condición del experimento acompaña a la tarea durante toda su vida.
            properties.setHeader(JobHeaders.FAIL_PROBABILITY, failProbability);
        }

        publisher.publish(target.exchange(), target.routingKey(),
                new Message(original.getBody(), properties), task.taskId());
        return target;
    }
}
