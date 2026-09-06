package com.eventlab.jobforge.contracts.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventlab.jobforge.contracts.message.JobHeaders;
import com.eventlab.jobforge.contracts.message.JobPriority;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Dos comportamientos de la cola de trabajo que no dependen del código del
 * worker sino de los argumentos con los que se declaró la cola.
 */
@Testcontainers
@SpringBootTest(properties = "job-forge.topology.delivery-limit=3")
class WorkQueueBehaviourIT {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4.3.5-management-alpine")
                    .asCompatibleSubstituteFor("rabbitmq"));

    private static final int DELIVERY_LIMIT = 3;

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
    private ConnectionFactory connectionFactory;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    /**
     * El broker es uno solo para toda la clase: sin vaciar, el segundo método
     * cuenta los mensajes que dejó el primero y la prueba pasa o falla según el
     * orden en que JUnit decida ejecutarlos.
     */
    @BeforeEach
    void vaciarColas() {
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(JobsTopology.WORK_QUEUE);
        rabbitAdmin.purgeQueue(JobsTopology.DEAD_QUEUE);
    }

    /**
     * Reencolar no es reintentar, y esta prueba fija por qué.
     *
     * <p>La reacción intuitiva ante un fallo es rechazar el mensaje pidiendo que
     * se reencole, confiando en que {@code x-delivery-limit} acabe cortando. No
     * corta. Con un {@code nack} de reencolado, RabbitMQ 4.x incrementa
     * {@code x-acquired-count} —cuántas veces alguien tomó el mensaje— y no el
     * contador de entregas del que depende el límite. El mensaje vuelve a la
     * cabeza de la cola indefinidamente, monopoliza el prefetch del worker y
     * bloquea al resto del pool. Aquí se comprueba con el límite en {@value
     * #DELIVERY_LIMIT}: pasadas muchas más entregas que eso, sigue vivo.
     *
     * <p>Esa es la razón de fondo de que el diseño <strong>republique</strong> al
     * exchange de reintentos en lugar de reencolar. No es solo para escalonar la
     * espera: es la única manera de que los reintentos terminen. El contador que
     * manda es {@code x-attempt}, que lo lleva el worker.
     *
     * <p>{@code x-delivery-limit} sigue declarado porque cubre el otro caso, el
     * del worker que muere sin decir nada y pierde la conexión. Eso se verifica
     * en F2, matando el proceso a media tarea.
     */
    @Test
    void reencolar_sin_parar_no_agota_el_limite_de_entregas() {
        publish(JobsTopology.JOBS_EXCHANGE, JobsTopology.WORK_ROUTING_KEY,
                "tarea-que-siempre-falla", JobPriority.NORMAL);

        AtomicInteger deliveries = new AtomicInteger();
        SimpleMessageListenerContainer worker = new SimpleMessageListenerContainer(connectionFactory);
        worker.setQueueNames(JobsTopology.WORK_QUEUE);
        worker.setPrefetchCount(1);
        // Al lanzar una excepción, Spring AMQP rechaza el mensaje reencolándolo.
        worker.setDefaultRequeueRejected(true);
        worker.setMessageListener(message -> {
            deliveries.incrementAndGet();
            throw new IllegalStateException("fallo permanente de la tarea");
        });

        worker.start();
        try {
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> deliveries.get() > DELIVERY_LIMIT * 10);
        } finally {
            worker.stop();
        }

        assertThat(queueDepth(JobsTopology.DEAD_QUEUE))
                .as("reencolar %d veces con el límite en %d no manda nada a la cola muerta",
                        deliveries.get(), DELIVERY_LIMIT)
                .isZero();
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(queueDepth(JobsTopology.WORK_QUEUE))
                        .as("y la tarea sigue ahí, delante de todas las demás")
                        .isEqualTo(1));
    }

    /**
     * Evidencia en CI de la desviación documentada en el ADR 003: las colas
     * quorum rechazan {@code x-max-priority}, pero sí ordenan por la propiedad
     * {@code priority} del mensaje. Si una actualización de RabbitMQ cambiara
     * esto, esta prueba lo dice antes que el demo en vivo.
     */
    @Test
    void una_tarea_urgente_adelanta_a_las_normales() {
        publish(JobsTopology.JOBS_EXCHANGE, JobsTopology.WORK_ROUTING_KEY, "normal-1", JobPriority.NORMAL);
        publish(JobsTopology.JOBS_EXCHANGE, JobsTopology.WORK_ROUTING_KEY, "normal-2", JobPriority.NORMAL);
        publish(JobsTopology.JOBS_EXCHANGE, JobsTopology.WORK_ROUTING_KEY, "urgente", JobPriority.HIGH);

        // Sin esta espera la prueba mediría una carrera, no una prioridad: las tres
        // tienen que estar encoladas antes de que nadie consuma.
        await().atMost(Duration.ofSeconds(10))
                .until(() -> queueDepth(JobsTopology.WORK_QUEUE) == 3);

        Message first = rabbitTemplate.receive(JobsTopology.WORK_QUEUE, 5_000);

        assertThat(first).isNotNull();
        assertThat(new String(first.getBody(), StandardCharsets.UTF_8))
                .as("publicada la última, entregada la primera")
                .isEqualTo("urgente");
    }

    private long queueDepth(String queue) {
        Long count = rabbitTemplate.execute(channel -> channel.messageCount(queue));
        return count == null ? 0L : count;
    }

    private void publish(String exchange, String routingKey, String body, JobPriority priority) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setPriority(priority.amqpPriority());
        properties.setHeader(JobHeaders.ATTEMPT, 0);
        rabbitTemplate.send(exchange, routingKey,
                new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }
}
