package com.eventlab.jobforge.api.panel;

import com.eventlab.jobforge.contracts.topology.JobsTopology;
import com.eventlab.jobforge.contracts.topology.RetryStage;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publica cada segundo la profundidad de todas las colas.
 *
 * <p>Se pregunta al broker en vez de llevar la cuenta en memoria porque la cuenta
 * en memoria mentiría en cuanto hubiera más de una réplica del API, y porque los
 * mensajes que están esperando en los tramos de reintento no pasan por aquí: los
 * mueve el propio broker cuando vence el TTL. Esa columna del panel no se puede
 * derivar de nada que ocurra en este proceso.
 */
@Component
public class QueueSnapshotPublisher {

    private static final Logger log = LoggerFactory.getLogger(QueueSnapshotPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final PanelBroadcaster broadcaster;

    public QueueSnapshotPublisher(RabbitTemplate rabbitTemplate, PanelBroadcaster broadcaster) {
        this.rabbitTemplate = rabbitTemplate;
        this.broadcaster = broadcaster;
    }

    @Scheduled(fixedRateString = "${job-forge.panel.snapshot-interval:1s}")
    public void publish() {
        if (broadcaster.openSessions() == 0) {
            // Nadie mirando, nadie a quien preguntar: no se molesta al broker.
            return;
        }
        try {
            Map<String, Long> queues = new LinkedHashMap<>();
            queues.put(JobsTopology.WORK_QUEUE, depth(JobsTopology.WORK_QUEUE));
            for (RetryStage stage : RetryStage.values()) {
                queues.put(stage.queueName(), depth(stage.queueName()));
            }
            queues.put(JobsTopology.DEAD_QUEUE, depth(JobsTopology.DEAD_QUEUE));

            broadcaster.broadcast(PanelSnapshot.of(queues, broadcaster.openSessions()));
        } catch (AmqpException e) {
            // El panel se queda con la última foto en vez de romperse. Un broker
            // caído ya se ve en Grafana; aquí solo dejaría de refrescar.
            log.debug("no se pudo tomar la foto de las colas: {}", e.getMessage());
        }
    }

    private long depth(String queue) {
        Long count = rabbitTemplate.execute(channel -> channel.messageCount(queue));
        return count == null ? 0L : count;
    }
}
