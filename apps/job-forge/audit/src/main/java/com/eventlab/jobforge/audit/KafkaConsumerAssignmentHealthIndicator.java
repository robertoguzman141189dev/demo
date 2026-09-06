package com.eventlab.jobforge.audit;

import java.util.Collection;
import java.util.List;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * La readiness del proyector: {@code true} solo cuando este pod tiene particiones
 * asignadas.
 *
 * <p>"El contexto de Spring levantó" no sirve como respuesta. Un consumidor de
 * Kafka puede estar perfectamente arrancado y con cero particiones durante un
 * rebalance, y en ese hueco no procesa ni un evento. Si Kubernetes lo diera por
 * listo, un despliegue progresivo mataría al pod viejo mientras el nuevo todavía
 * no consume, y la proyección se quedaría parada sin que nadie lo notase.
 *
 * <p><b>Restricción que esto impone:</b> las réplicas no pueden superar el número
 * de particiones de {@code trabajos.eventos}, que son tres. La cuarta réplica no
 * recibiría ninguna partición, jamás se declararía lista y bloquearía el
 * despliegue. No es un defecto del indicador: es la regla de Kafka hecha visible,
 * la misma que en F7 impide que KEDA escale más allá de las particiones.
 */
@Component
public class KafkaConsumerAssignmentHealthIndicator implements HealthIndicator {

    private final KafkaListenerEndpointRegistry registry;

    public KafkaConsumerAssignmentHealthIndicator(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        if (containers.isEmpty()) {
            return Health.down().withDetail("motivo", "no hay contenedores de escucha registrados").build();
        }

        for (MessageListenerContainer container : containers) {
            String id = container.getListenerId();

            if (!container.isRunning()) {
                return Health.down()
                        .withDetail("listener", id)
                        .withDetail("motivo", "el contenedor de escucha está parado")
                        .build();
            }

            // Devuelve null mientras el grupo no ha repartido, que es justo el
            // estado que este indicador existe para delatar.
            Collection<TopicPartition> assigned = container.getAssignedPartitions();
            if (assigned == null || assigned.isEmpty()) {
                return Health.down()
                        .withDetail("listener", id)
                        .withDetail("motivo", "sin particiones asignadas todavía")
                        .build();
            }
        }

        return Health.up().withDetail("particiones", describeAssignments(containers)).build();
    }

    private static List<String> describeAssignments(Collection<MessageListenerContainer> containers) {
        return containers.stream()
                .map(MessageListenerContainer::getAssignedPartitions)
                .filter(assigned -> assigned != null)
                .flatMap(Collection::stream)
                .map(TopicPartition::toString)
                .sorted()
                .toList();
    }
}
