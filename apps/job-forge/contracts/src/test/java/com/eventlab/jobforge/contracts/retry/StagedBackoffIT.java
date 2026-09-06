package com.eventlab.jobforge.contracts.retry;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventlab.jobforge.contracts.message.JobHeaders;
import com.eventlab.jobforge.contracts.topology.JobTopologyProperties;
import com.eventlab.jobforge.contracts.topology.JobsTopology;
import com.eventlab.jobforge.contracts.topology.RetryStage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Prueba obligatoria: el backoff escalonado respeta los tiempos.
 *
 * <p>Los tramos se comprimen a 1 s, 2 s y 4 s. No es una trampa para que la
 * prueba pase: lo que se verifica aquí es el <em>mecanismo</em> —que la espera
 * ocurre, que crece a cada intento y que el mensaje vuelve solo a la cola de
 * trabajo— y ese mecanismo no cambia porque el tramo dure segundos o minutos.
 * Que los tiempos declarados sean 5 s, 30 s y 2 min lo verifica
 * {@code TopologyDeclarationIT} contra los valores reales. Una prueba que
 * esperase 2 minutos no probaría nada más y nadie la ejecutaría.
 *
 * <p>Fíjate en que aquí no hay ningún {@code Thread.sleep} propio, ni scheduler,
 * ni tabla de tareas pendientes. El único reloj es el broker.
 */
@Testcontainers
@SpringBootTest(properties = {
        "job-forge.topology.retry-delays.five-seconds=1s",
        "job-forge.topology.retry-delays.thirty-seconds=2s",
        "job-forge.topology.retry-delays.two-minutes=4s"
})
class StagedBackoffIT {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4.3.5-management-alpine")
                    .asCompatibleSubstituteFor("rabbitmq"));

    /** Margen por debajo: mide el reloj de la prueba, no el del broker. */
    private static final Duration EARLY_TOLERANCE = Duration.ofMillis(300);

    /** Margen por arriba: arranque del consumidor y viaje por el dead letter exchange. */
    private static final Duration LATE_TOLERANCE = Duration.ofSeconds(3);

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JobTopologyProperties properties;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    /**
     * Los dos métodos comparten broker y el segundo deja una tarea esperando en
     * un tramo que reaparece un segundo después. Sin vaciar, esa tarea se cuela
     * en el otro método y la prueba mide otra cosa.
     */
    @BeforeEach
    void vaciarColas() {
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(JobsTopology.WORK_QUEUE);
        rabbitAdmin.purgeQueue(JobsTopology.DEAD_QUEUE);
        for (RetryStage stage : RetryStage.values()) {
            rabbitAdmin.purgeQueue(stage.queueName());
        }
    }

    @Test
    void cada_reintento_espera_su_tramo_y_el_siguiente_espera_mas() {
        byte[] body = "tarea-de-prueba".getBytes(StandardCharsets.UTF_8);

        publish(JobsTopology.JOBS_EXCHANGE, JobsTopology.WORK_ROUTING_KEY, body, 0);
        Message message = receive(JobsTopology.WORK_QUEUE, Duration.ofSeconds(10));
        assertThat(message).as("la tarea recién publicada llega a la cola de trabajo").isNotNull();
        assertThat(attemptOf(message)).isZero();

        Duration previousDelay = Duration.ZERO;

        for (int attempt = 1; attempt <= RetryStage.maxAttempts(); attempt++) {
            RetryTarget target = RetryRouting.targetFor(attempt, "fallo inyectado");
            assertThat(target).isInstanceOf(RetryTarget.Delayed.class);

            RetryStage stage = ((RetryTarget.Delayed) target).stage();
            Duration expected = properties.delayOf(stage);
            assertThat(expected)
                    .as("el intento %d debe esperar más que el anterior", attempt)
                    .isGreaterThan(previousDelay);

            // El worker falla: republica al tramo que toca y solo entonces acusa
            // el original. Aquí el acuse es implícito porque receive() consume.
            long startedAt = System.nanoTime();
            publish(target.exchange(), target.routingKey(), body, attempt);

            message = receive(JobsTopology.WORK_QUEUE, expected.plus(LATE_TOLERANCE).plusSeconds(5));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(message)
                    .as("el tramo %s debe devolver la tarea sola, sin que nadie la lea", stage.queueName())
                    .isNotNull();
            assertThat(attemptOf(message))
                    .as("el contador de intentos viaja en el header, no en el cuerpo")
                    .isEqualTo(attempt);
            assertThat(elapsed)
                    .as("volvió antes de tiempo: el TTL del tramo %s no se respetó", stage.queueName())
                    .isGreaterThanOrEqualTo(expected.minus(EARLY_TOLERANCE));
            assertThat(elapsed)
                    .as("tardó de más en volver del tramo %s", stage.queueName())
                    .isLessThan(expected.plus(LATE_TOLERANCE));

            previousDelay = expected;
        }

        // Cuarto fallo: ya no quedan tramos.
        RetryTarget terminal = RetryRouting.targetFor(RetryStage.maxAttempts() + 1, "fallo inyectado");
        assertThat(terminal).isInstanceOf(RetryTarget.Dead.class);

        publish(terminal.exchange(), terminal.routingKey(), body, RetryStage.maxAttempts() + 1);
        assertThat(receive(JobsTopology.DEAD_QUEUE, Duration.ofSeconds(5)))
                .as("agotados los intentos, la tarea espera inspección humana")
                .isNotNull();
    }

    @Test
    void una_tarea_en_espera_es_invisible_para_los_workers() {
        publish(JobsTopology.RETRY_EXCHANGE, RetryStage.FIVE_SECONDS.routingKey(),
                "tarea-en-espera".getBytes(StandardCharsets.UTF_8), 1);

        // Mientras cumple su castigo no está en jobs.work: ningún worker la ve, no
        // consume prefetch y no bloquea a nadie. Ese es todo el punto de que el
        // tramo de espera sea una cola aparte.
        assertThat(receive(JobsTopology.WORK_QUEUE, Duration.ofMillis(400)))
                .as("no debería haber nada consumible antes de que venza el TTL")
                .isNull();
    }

    private void publish(String exchange, String routingKey, byte[] body, int attempt) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader(JobHeaders.ATTEMPT, attempt);
        properties.setHeader(JobHeaders.TASK_ID, "task-1");
        rabbitTemplate.send(exchange, routingKey, new Message(body, properties));
    }

    private Message receive(String queue, Duration timeout) {
        return rabbitTemplate.receive(queue, timeout.toMillis());
    }

    private static int attemptOf(Message message) {
        Object attempt = message.getMessageProperties().getHeader(JobHeaders.ATTEMPT);
        return attempt == null ? -1 : ((Number) attempt).intValue();
    }
}
