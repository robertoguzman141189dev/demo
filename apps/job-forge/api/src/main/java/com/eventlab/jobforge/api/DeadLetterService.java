package com.eventlab.jobforge.api;

import com.eventlab.jobforge.contracts.audit.JobEventPublisher;
import com.eventlab.jobforge.contracts.audit.JobEventType;
import com.eventlab.jobforge.contracts.message.JobHeaders;
import com.eventlab.jobforge.contracts.publish.ConfirmedPublisher;
import com.eventlab.jobforge.contracts.publish.MessageNotConfirmedException;
import com.eventlab.jobforge.contracts.topology.JobsTopology;
import com.rabbitmq.client.GetResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Inspección y reproceso de la cola muerta. Es la única salida de {@code
 * jobs.dead}, y es deliberadamente manual: una tarea llega ahí porque agotó todos
 * sus intentos, y devolverla al circuito automáticamente solo repetiría el
 * fracaso más deprisa.
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final RabbitTemplate rabbitTemplate;
    private final ConfirmedPublisher publisher;
    private final ApiProperties properties;
    private final JobEventPublisher events;

    public DeadLetterService(RabbitTemplate rabbitTemplate,
                             ConfirmedPublisher publisher,
                             ApiProperties properties,
                             JobEventPublisher events) {
        this.rabbitTemplate = rabbitTemplate;
        this.publisher = publisher;
        this.properties = properties;
        this.events = events;
    }

    /**
     * Mira sin consumir: saca los mensajes y los devuelve rechazándolos con
     * reencolado. Es un vistazo, no una lectura: si otro cliente inspecciona a la
     * vez, puede ver los mismos mensajes.
     */
    public List<DeadLetterView> peek(int limit) {
        int bounded = Math.clamp(limit, 1, properties.getMaxReprocessBatch());
        List<DeadLetterView> found = new ArrayList<>();

        rabbitTemplate.execute(channel -> {
            List<Long> tags = new ArrayList<>();
            for (int i = 0; i < bounded; i++) {
                GetResponse response = channel.basicGet(JobsTopology.DEAD_QUEUE, false);
                if (response == null) {
                    break;
                }
                tags.add(response.getEnvelope().getDeliveryTag());
                found.add(toView(response));
            }
            // Se devuelven a la cola en el mismo estado en que estaban.
            for (Long tag : tags) {
                channel.basicNack(tag, false, true);
            }
            return null;
        });

        return found;
    }

    /**
     * Reinyecta tareas muertas al exchange de entrada con el contador de intentos
     * a cero: para el sistema es un trabajo nuevo, y vuelve a tener derecho a los
     * tres tramos de reintento.
     *
     * <p>El orden vuelve a ser el de siempre: publicar, confirmar y solo entonces
     * acusar el mensaje muerto. Al revés, un fallo de publicación borraría de la
     * cola muerta una tarea que no llegó a renacer, y sería la pérdida definitiva:
     * de ahí ya no hay red debajo.
     */
    public int reprocess(int limit) {
        int bounded = Math.clamp(limit, 1, properties.getMaxReprocessBatch());

        Integer reprocessed = rabbitTemplate.execute(channel -> {
            int count = 0;
            for (int i = 0; i < bounded; i++) {
                GetResponse response = channel.basicGet(JobsTopology.DEAD_QUEUE, false);
                if (response == null) {
                    break;
                }
                long tag = response.getEnvelope().getDeliveryTag();
                String taskId = header(response, JobHeaders.TASK_ID, "desconocida");
                try {
                    publisher.publish(JobsTopology.JOBS_EXCHANGE, JobsTopology.WORK_ROUTING_KEY,
                            revive(response), taskId);
                    events.publish(header(response, JobHeaders.JOB_ID, "desconocido"), taskId,
                            JobEventType.REPROCESSED, 0, "reinyectada a mano desde la cola muerta");
                    channel.basicAck(tag, false);
                    count++;
                } catch (MessageNotConfirmedException e) {
                    log.error("no se pudo revivir la tarea {}; se queda en la cola muerta", taskId, e);
                    channel.basicNack(tag, false, true);
                    break;
                }
            }
            return count;
        });

        int total = reprocessed == null ? 0 : reprocessed;
        log.info("reprocesadas {} tareas desde la cola muerta", total);
        return total;
    }

    private static Message revive(GetResponse response) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setContentType(response.getProps().getContentType());
        properties.setPriority(response.getProps().getPriority() == null
                ? 0 : response.getProps().getPriority());
        properties.setHeader(JobHeaders.JOB_ID, header(response, JobHeaders.JOB_ID, null));
        properties.setHeader(JobHeaders.TASK_ID, header(response, JobHeaders.TASK_ID, null));
        // Empieza de cero: el reproceso manual es una segunda oportunidad completa.
        properties.setHeader(JobHeaders.ATTEMPT, 0);
        return new Message(response.getBody(), properties);
    }

    private static DeadLetterView toView(GetResponse response) {
        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        return new DeadLetterView(
                header(response, JobHeaders.TASK_ID, null),
                header(response, JobHeaders.JOB_ID, null),
                intHeader(response, JobHeaders.ATTEMPT),
                header(response, JobHeaders.FAILURE_REASON, null),
                body.length() > 200 ? body.substring(0, 200) : body);
    }

    private static String header(GetResponse response, String name, String fallback) {
        Map<String, Object> headers = response.getProps().getHeaders();
        Object value = headers == null ? null : headers.get(name);
        return value == null ? fallback : value.toString();
    }

    private static int intHeader(GetResponse response, String name) {
        Map<String, Object> headers = response.getProps().getHeaders();
        Object value = headers == null ? null : headers.get(name);
        return value instanceof Number number ? number.intValue() : -1;
    }
}
