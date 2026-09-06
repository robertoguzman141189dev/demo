package com.eventlab.jobforge.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventlab.jobforge.contracts.audit.AuditTopics;
import com.eventlab.jobforge.contracts.audit.TaskState;
import com.eventlab.jobforge.contracts.audit.TaskState.TaskStatus;
import com.eventlab.jobforge.contracts.message.JobHeaders;
import com.eventlab.jobforge.contracts.message.JobTask;
import com.eventlab.jobforge.contracts.topology.JobsTopology;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * La prueba que cierra la limitación que F2 dejó escrita.
 *
 * <p>Se siembra en el topic compactado el estado de una tarea que ya se completó
 * —como si la hubiera hecho un worker que después murió— y se arranca un worker
 * nuevo, con la memoria vacía. Al llegarle esa misma tarea, la reconoce y no la
 * repite: su registro de tareas completadas ya no vive solo en la memoria del
 * proceso, se puede volver a calcular leyendo el log.
 *
 * <p>Se siembran además dos versiones de la misma clave, en orden, porque la
 * compactación es eventual: el segmento activo no se compacta y el lector va a
 * ver las dos. Quedarse con la primera en vez de con la última es el error
 * clásico, y aquí quedaría en evidencia — la primera versión dice que la tarea
 * estaba reintentándose.
 */
@Testcontainers
@SpringBootTest(properties = {
        "job-forge.worker.task-duration=0s",
        "job-forge.worker.task-jitter=0s"
})
@Import(CountingProcessorConfiguration.class)
class StateRebuildIT {

    private static final String COMPLETED_TASK = "task-hecha-por-otro";

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4.3.5-management-alpine")
                    .asCompatibleSubstituteFor("rabbitmq"));

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        KafkaSupport.register(registry);
    }

    /**
     * Se ejecuta antes de que Spring cree el contexto, que es lo que da sentido a
     * la prueba: el estado tiene que existir <em>antes</em> de que el worker
     * arranque, igual que en producción.
     */
    @BeforeAll
    static void sembrarEstadoDeUnWorkerAnterior() throws Exception {
        crearTopicCompactado();

        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaSupport.KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        try (KafkaProducer<String, Object> producer = new KafkaProducer<>(config)) {
            // Versión antigua de la misma clave: si el lector se quedara con esta,
            // la tarea se volvería a ejecutar.
            producer.send(new ProducerRecord<>(AuditTopics.STATE, COMPLETED_TASK,
                    new TaskState(COMPLETED_TASK, "job-anterior", TaskStatus.RETRYING, 1,
                            Instant.now(), "esperando en el primer tramo"))).get();
            producer.send(new ProducerRecord<>(AuditTopics.STATE, COMPLETED_TASK,
                    new TaskState(COMPLETED_TASK, "job-anterior", TaskStatus.COMPLETED, 2,
                            Instant.now(), "completada por un worker que ya no existe"))).get();
        }
    }

    private static void crearTopicCompactado() throws InterruptedException, ExecutionException {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KafkaSupport.KAFKA.getBootstrapServers()))) {
            NewTopic topic = new NewTopic(AuditTopics.STATE, 3, (short) 1)
                    .configs(Map.of(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT));
            try {
                admin.createTopics(List.of(topic)).all().get();
            } catch (ExecutionException e) {
                // Ya existe: otra clase de prueba lo creó antes. Es lo esperable al
                // compartir broker.
            }
        }
    }

    @Autowired
    private CountingTaskProcessor processor;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private MessageConverter converter;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private StateRebuildRunner rebuild;

    @BeforeEach
    void prepararTopologia() {
        rabbitAdmin.initialize();
    }

    @Test
    void un_worker_recien_arrancado_sabe_lo_que_hizo_el_anterior() {
        assertThat(rebuild.health().getStatus().getCode())
                .as("la readiness solo pasa a verde cuando la reconstrucción termina")
                .isEqualTo("UP");

        publishTask(COMPLETED_TASK);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(taskCounter("duplicate"))
                        .as("debería reconocerla como ya hecha sin trabajar")
                        .isEqualTo(1.0d));

        assertThat(processor.starts.get())
                .as("y no ejecutarla: el estado sobrevivió al proceso que lo generó")
                .isZero();
    }

    @Test
    void una_tarea_que_nadie_ha_hecho_si_se_procesa() {
        publishTask("task-nueva");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(taskCounter("completed"))
                        .as("el estado reconstruido no puede bloquear trabajo legítimo")
                        .isEqualTo(1.0d));
    }

    private double taskCounter(String outcome) {
        var counter = meters.find("jobforge.tasks").tag("outcome", outcome).counter();
        return counter == null ? 0.0d : counter.count();
    }

    private void publishTask(String taskId) {
        JobTask task = new JobTask("job-nuevo", taskId, 0, "contenido sintético");
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader(JobHeaders.JOB_ID, task.jobId());
        properties.setHeader(JobHeaders.TASK_ID, task.taskId());
        properties.setHeader(JobHeaders.ATTEMPT, 0);
        properties.setHeader(JobHeaders.FAIL_PROBABILITY, 0);
        Message message = converter.toMessage(task, properties);
        rabbitTemplate.send(JobsTopology.JOBS_EXCHANGE, JobsTopology.WORK_ROUTING_KEY, message);
    }
}
