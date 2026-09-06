package com.eventlab.jobforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventlab.jobforge.contracts.message.JobHeaders;
import com.eventlab.jobforge.contracts.message.JobTask;
import com.eventlab.jobforge.contracts.topology.JobsTopology;
import java.time.Duration;
import java.util.List;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * La única salida de la cola muerta, y es manual a propósito: una tarea llega ahí
 * porque agotó todos sus intentos, y devolverla al circuito automáticamente solo
 * repetiría el fracaso más deprisa.
 */
@Testcontainers
@SpringBootTest
class DeadLetterReprocessIT {

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

    @Autowired
    private DeadLetterService deadLetters;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private MessageConverter converter;

    @BeforeEach
    void prepararTopologia() {
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(JobsTopology.WORK_QUEUE);
        rabbitAdmin.purgeQueue(JobsTopology.DEAD_QUEUE);
    }

    @Test
    void inspeccionar_la_cola_muerta_no_la_vacia() {
        publishDeadTask("task-muerta", 4);

        List<DeadLetterView> first = deadLetters.peek(10);
        List<DeadLetterView> second = deadLetters.peek(10);

        assertThat(first).hasSize(1);
        assertThat(first.getFirst().taskId()).isEqualTo("task-muerta");
        assertThat(first.getFirst().attempts()).isEqualTo(4);
        assertThat(first.getFirst().failureReason()).contains("intentos agotados");
        assertThat(second)
                .as("mirar no consume: la segunda inspección ve lo mismo")
                .hasSize(1);
    }

    @Test
    void reprocesar_devuelve_la_tarea_al_circuito_con_los_intentos_a_cero() {
        publishDeadTask("task-resucitada", 4);

        int reprocessed = deadLetters.reprocess(10);

        assertThat(reprocessed).isEqualTo(1);

        Message revived = rabbitTemplate.receive(JobsTopology.WORK_QUEUE, 5_000);
        assertThat(revived).as("la tarea vuelve a la cola de trabajo").isNotNull();

        Integer attempt = revived.getMessageProperties().getHeader(JobHeaders.ATTEMPT);
        String taskId = revived.getMessageProperties().getHeader(JobHeaders.TASK_ID);

        assertThat(attempt)
                .as("el reproceso manual es una segunda oportunidad completa, con sus tres tramos")
                .isZero();
        assertThat(taskId)
                .as("la identidad no cambia: si el worker ya la completó alguna vez, lo sabrá")
                .isEqualTo("task-resucitada");
        assertThat(deadLetters.peek(10))
                .as("y sale de la cola muerta solo cuando la publicación está confirmada")
                .isEmpty();
    }

    @Test
    void reprocesar_una_cola_vacia_no_hace_nada() {
        assertThat(deadLetters.reprocess(10)).isZero();
    }

    private void publishDeadTask(String taskId, int attempts) {
        JobTask task = new JobTask("job-muerto", taskId, 0, "contenido sintético");
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader(JobHeaders.JOB_ID, task.jobId());
        properties.setHeader(JobHeaders.TASK_ID, task.taskId());
        properties.setHeader(JobHeaders.ATTEMPT, attempts);
        properties.setHeader(JobHeaders.FAILURE_REASON, "intentos agotados tras 4 de 3: fallo inyectado");
        rabbitTemplate.send(JobsTopology.DEAD_LETTER_EXCHANGE, JobsTopology.DEAD_ROUTING_KEY,
                converter.toMessage(task, properties));

        // Publicar es asíncrono: sin esperar, la prueba mediría una carrera.
        await().atMost(Duration.ofSeconds(10)).until(() -> queueDepth(JobsTopology.DEAD_QUEUE) == 1);
    }

    private long queueDepth(String queue) {
        Long count = rabbitTemplate.execute(channel -> channel.messageCount(queue));
        return count == null ? 0L : count;
    }
}
