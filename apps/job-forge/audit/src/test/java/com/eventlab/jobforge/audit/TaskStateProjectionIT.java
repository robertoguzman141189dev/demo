package com.eventlab.jobforge.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventlab.jobforge.contracts.audit.AuditTopics;
import com.eventlab.jobforge.contracts.audit.JobEventPublisher;
import com.eventlab.jobforge.contracts.audit.JobEventType;
import com.eventlab.jobforge.contracts.audit.TaskState;
import com.eventlab.jobforge.contracts.audit.TaskState.TaskStatus;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * El patrón que justifica a Kafka aquí: el log es la verdad, el estado es una
 * conclusión que se deriva de él.
 */
@Testcontainers
@SpringBootTest
class TaskStateProjectionIT {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("job-forge.audit.replication-factor", () -> 1);
    }

    @Autowired
    private JobEventPublisher events;

    @Test
    void el_estado_de_una_tarea_es_el_ultimo_hecho_de_su_historia() {
        String taskId = "task-con-historia";

        // Los tres hechos van con la misma clave, así que caen en la misma
        // partición y llegan en este orden. Sin esa garantía, el estado final
        // dependería del azar.
        events.publish("job-1", taskId, JobEventType.SUBMITTED, 0, "aceptada");
        events.publish("job-1", taskId, JobEventType.RETRY_SCHEDULED, 1, "falló y espera 5s");
        events.publish("job-1", taskId, JobEventType.COMPLETED, 2, "completada en el segundo intento");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            TaskState state = latestStateOf(taskId);
            assertThat(state).as("la proyección debe haber escrito el estado").isNotNull();
            assertThat(state.status())
                    .as("el último hecho manda, no el primero")
                    .isEqualTo(TaskStatus.COMPLETED);
            assertThat(state.attempts()).isEqualTo(2);
        });
    }

    @Test
    void una_tarea_que_muere_queda_marcada_como_muerta() {
        String taskId = "task-sin-suerte";

        events.publish("job-2", taskId, JobEventType.SUBMITTED, 0, "aceptada");
        events.publish("job-2", taskId, JobEventType.DEAD_LETTERED, 4, "intentos agotados");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(latestStateOf(taskId))
                        .isNotNull()
                        .extracting(TaskState::status)
                        .isEqualTo(TaskStatus.DEAD));
    }

    @Test
    void el_topic_de_estado_es_compactado_y_el_de_eventos_no() throws Exception {
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {

            assertThat(policyOf(admin, AuditTopics.STATE))
                    .as("sin compactación, el estado no se podría reconstruir sin leerlo todo para siempre")
                    .isEqualTo(TopicConfig.CLEANUP_POLICY_COMPACT);
            assertThat(policyOf(admin, AuditTopics.EVENTS))
                    .as("la historia caduca por tiempo: es auditoría, no un archivo eterno")
                    .isEqualTo(TopicConfig.CLEANUP_POLICY_DELETE);
        }
    }

    private static String policyOf(Admin admin, String topic) throws Exception {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        Config config = admin.describeConfigs(List.of(resource)).all().get().get(resource);
        return config.get(TopicConfig.CLEANUP_POLICY_CONFIG).value();
    }

    /**
     * Lee el topic compactado entero y se queda con la última versión de la clave,
     * que es justo lo que hace el worker al arrancar.
     */
    private static TaskState latestStateOf(String taskId) {
        Map<String, Object> config = new HashMap<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class,
                JsonDeserializer.VALUE_DEFAULT_TYPE, TaskState.class.getName(),
                JsonDeserializer.USE_TYPE_INFO_HEADERS, false,
                JsonDeserializer.TRUSTED_PACKAGES, "com.eventlab.jobforge.contracts.audit"));

        try (KafkaConsumer<String, TaskState> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = consumer.partitionsFor(AuditTopics.STATE).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            TaskState latest = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(300));
                if (records.isEmpty() && latest != null) {
                    break;
                }
                for (ConsumerRecord<String, TaskState> record : records) {
                    if (taskId.equals(record.key())) {
                        latest = record.value();
                    }
                }
            }
            return latest;
        }
    }
}
