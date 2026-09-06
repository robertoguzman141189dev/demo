package com.eventlab.jobforge.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventlab.jobforge.contracts.message.JobHeaders;
import com.eventlab.jobforge.contracts.message.JobTask;
import com.eventlab.jobforge.contracts.topology.JobsTopology;
import com.eventlab.jobforge.contracts.topology.RetryStage;
import java.time.Duration;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * El ciclo de vida completo de una tarea que siempre falla: cuatro intentos, tres
 * esperas y la cola muerta. Es F1 y F2 funcionando juntos.
 *
 * <p>Los tramos se comprimen a medio segundo. Lo que se verifica aquí es que el
 * worker escala de tramo y se rinde cuando toca; que los tiempos declarados sean
 * 5 s, 30 s y 2 min lo verifica {@code TopologyDeclarationIT} en el módulo de
 * contratos.
 */
@Testcontainers
@SpringBootTest(properties = {
        "job-forge.worker.task-duration=0s",
        "job-forge.worker.task-jitter=0s",
        "job-forge.topology.retry-delays.five-seconds=500ms",
        "job-forge.topology.retry-delays.thirty-seconds=500ms",
        "job-forge.topology.retry-delays.two-minutes=500ms"
})
@Import(CountingProcessorConfiguration.class)
class RetryEscalationIT {

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
    private CountingTaskProcessor processor;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private MessageConverter converter;

    @BeforeEach
    void prepararTopologia() {
        rabbitAdmin.initialize();
    }

    @Test
    void una_tarea_que_siempre_falla_recorre_los_tramos_y_termina_en_la_cola_muerta() {
        publishAlwaysFailingTask("task-condenada");

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(queueDepth(JobsTopology.DEAD_QUEUE))
                        .as("intentos hasta ahora: %d", processor.starts.get())
                        .isEqualTo(1));

        assertThat(processor.starts.get())
                .as("un intento inicial más un reintento por tramo")
                .isEqualTo(RetryStage.maxAttempts() + 1);

        Message dead = rabbitTemplate.receive(JobsTopology.DEAD_QUEUE, 5_000);
        assertThat(dead).isNotNull();

        // getHeader() es genérico: sin tipar la variable, el compilador no sabe
        // qué sobrecarga de assertThat elegir.
        Integer attempts = dead.getMessageProperties().getHeader(JobHeaders.ATTEMPT);
        String reason = dead.getMessageProperties().getHeader(JobHeaders.FAILURE_REASON);
        String taskId = dead.getMessageProperties().getHeader(JobHeaders.TASK_ID);

        assertThat(attempts)
                .as("el contador que llega a la cola muerta es el nuestro, no el del broker")
                .isEqualTo(RetryStage.maxAttempts() + 1);
        assertThat(reason)
                .as("y viene con el motivo, para que alguien pueda decidir qué hacer")
                .contains("fallo inyectado");
        assertThat(taskId)
                .as("la identidad de la tarea sobrevive intacta a los cuatro intentos")
                .isEqualTo("task-condenada");
    }

    private long queueDepth(String queue) {
        Long count = rabbitTemplate.execute(channel -> channel.messageCount(queue));
        return count == null ? 0L : count;
    }

    private void publishAlwaysFailingTask(String taskId) {
        JobTask task = new JobTask("job-condenado", taskId, 0, "contenido sintético");
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader(JobHeaders.JOB_ID, task.jobId());
        properties.setHeader(JobHeaders.TASK_ID, task.taskId());
        properties.setHeader(JobHeaders.ATTEMPT, 0);
        properties.setHeader(JobHeaders.FAIL_PROBABILITY, 100);
        Message message = converter.toMessage(task, properties);
        rabbitTemplate.send(JobsTopology.JOBS_EXCHANGE, JobsTopology.WORK_ROUTING_KEY, message);
    }
}
