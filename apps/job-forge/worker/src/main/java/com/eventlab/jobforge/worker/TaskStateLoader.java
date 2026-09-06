package com.eventlab.jobforge.worker;

import com.eventlab.jobforge.contracts.audit.AuditTopics;
import com.eventlab.jobforge.contracts.audit.TaskState;
import com.eventlab.jobforge.contracts.audit.TaskState.TaskStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.stereotype.Component;

/**
 * Reconstruye el estado leyendo entero el topic compactado.
 *
 * <p>Esto es lo que cierra la limitación que F2 dejó escrita: el registro de
 * tareas completadas ya no muere con el proceso, porque se puede volver a
 * calcular. Un worker recién arrancado sabe lo que sabía el que murió.
 *
 * <p>Tres detalles que no son obvios:
 *
 * <ul>
 *   <li>Se usa {@code assign} y no un grupo de consumidores. Un grupo sirve para
 *       repartir trabajo entre varios; aquí cada réplica necesita <strong>todo</strong>
 *       el estado, no una parte. Además, un grupo recordaría offsets y la siguiente
 *       lectura empezaría donde lo dejó, que es lo contrario de lo que queremos.</li>
 *   <li>La compactación es eventual: el segmento activo nunca se compacta, así que
 *       una misma clave puede aparecer varias veces. Hay que quedarse con la
 *       <strong>última</strong> versión, no con la primera. Por eso se acumula en un
 *       mapa mientras se lee y se decide al final.</li>
 *   <li>Un valor {@code null} es un tombstone: la clave fue borrada. Se ignora.</li>
 * </ul>
 */
@Component
public class TaskStateLoader {

    private static final Logger log = LoggerFactory.getLogger(TaskStateLoader.class);

    private final KafkaProperties kafkaProperties;

    public TaskStateLoader(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    /**
     * @return cuántas tareas completadas se recuperaron
     * @throws StateRebuildFailedException si no se logra alcanzar el final del topic
     *                                     dentro del plazo. Se prefiere fallar y que
     *                                     el pod se reinicie a quedarse colgado para
     *                                     siempre esperando a Kafka
     */
    public int loadCompletedInto(ProcessedTaskRegistry registry, Duration timeout) {
        Map<String, TaskState> latestByTask = new HashMap<>();
        Instant deadline = Instant.now().plus(timeout);

        try (KafkaConsumer<String, TaskState> consumer = new KafkaConsumer<>(consumerConfig())) {
            // partitionsFor devuelve null si el topic no existe todavía: es el caso
            // del primer arranque contra un Kafka vacío.
            var partitionInfo = consumer.partitionsFor(AuditTopics.STATE);
            List<TopicPartition> partitions = partitionInfo == null ? List.of()
                    : partitionInfo.stream()
                            .map(info -> new TopicPartition(info.topic(), info.partition()))
                            .toList();
            if (partitions.isEmpty()) {
                log.warn("el topic {} no tiene particiones todavía; se arranca sin estado previo",
                        AuditTopics.STATE);
                return 0;
            }

            consumer.assign(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            consumer.seekToBeginning(partitions);

            while (!caughtUp(consumer, endOffsets)) {
                if (Instant.now().isAfter(deadline)) {
                    throw new StateRebuildFailedException(
                            "no se pudo leer %s completo en %s".formatted(AuditTopics.STATE, timeout));
                }
                ConsumerRecords<String, TaskState> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, TaskState> record : records) {
                    if (record.value() == null) {
                        latestByTask.remove(record.key());
                    } else {
                        latestByTask.put(record.key(), record.value());
                    }
                }
            }
        }

        int recovered = 0;
        for (TaskState state : latestByTask.values()) {
            if (state.status() == TaskStatus.COMPLETED) {
                registry.markProcessed(state.taskId());
                recovered++;
            }
        }
        log.info("estado reconstruido desde {}: {} tareas conocidas, {} ya completadas",
                AuditTopics.STATE, latestByTask.size(), recovered);
        return recovered;
    }

    private static boolean caughtUp(KafkaConsumer<String, TaskState> consumer,
                                    Map<TopicPartition, Long> endOffsets) {
        return endOffsets.entrySet().stream()
                .allMatch(entry -> consumer.position(entry.getKey()) >= entry.getValue());
    }

    private Map<String, Object> consumerConfig() {
        Map<String, Object> config = kafkaProperties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TaskState.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.eventlab.jobforge.contracts.audit");
        // Sin grupo: esta lectura no comparte trabajo con nadie ni recuerda por dónde iba.
        config.remove(ConsumerConfig.GROUP_ID_CONFIG);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return config;
    }
}
