package com.eventlab.jobforge.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eventlab.jobforge.contracts.message.JobHeaders;
import com.eventlab.jobforge.contracts.message.JobTask;
import com.eventlab.jobforge.contracts.topology.JobsTopology;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
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
 * Prueba obligatoria: matar el worker a media tarea y verificar la reentrega.
 *
 * <p>La muerte se simula cortando la conexión desde el broker con
 * {@code rabbitmqctl}, que es lo que le ocurre a un pod que desaparece: sin
 * cierre ordenado y sin despedida. Que corte el broker no es un detalle: un
 * cierre ordenado desde el cliente no reproduce el caso.
 *
 * <p>Se demuestran dos cosas encadenadas. La primera, que el trabajo no se
 * pierde: el acuse manual hace que el mensaje siga siendo del broker hasta el
 * final, y al caerse la conexión vuelve a la cola. La segunda, que la reentrega
 * <strong>no</strong> se traduce en trabajo repetido, porque el registro de
 * tareas completadas la reconoce como duplicada.
 *
 * <p>Con un matiz honesto: aquí muere la conexión, no el proceso, así que el
 * registro en memoria sobrevive. Cuando muere el pod entero ese registro se va
 * con él, y otra réplica sí volvería a ejecutar la tarea. Es exactamente la
 * limitación que documenta {@link ProcessedTaskRegistry} y que F3 resuelve
 * reconstruyendo el estado desde el topic compactado de Kafka.
 */
@Testcontainers
@SpringBootTest(properties = {
        "job-forge.worker.task-duration=3s",
        "job-forge.worker.task-jitter=0s"
})
@Import(CountingProcessorConfiguration.class)
class WorkerRedeliveryIT {

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

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void prepararTopologia() {
        rabbitAdmin.initialize();
    }

    @Test
    void un_worker_que_muere_a_media_tarea_no_pierde_el_trabajo() throws Exception {
        publishTask("task-supervivencia");

        assertThat(processor.firstStarted.await(30, TimeUnit.SECONDS))
                .as("el worker debería haber empezado a trabajar")
                .isTrue();

        killWorkerConnection();

        // Lo primero: la tarea reaparece en la cola. Sin acuse manual aquí no
        // habría nada, porque el broker la habría dado por entregada al escribirla
        // en el socket y el trabajo se habría perdido en silencio.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(queueDepth(JobsTopology.WORK_QUEUE))
                        .as("el broker debe devolver a la cola la tarea sin acusar")
                        .isEqualTo(1));

        // Lo segundo: la reentrega no se convierte en trabajo repetido.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(taskCounter("duplicate"))
                        .as("la reentrega debe reconocerse como duplicada; ejecuciones: %d",
                                processor.starts.get())
                        .isEqualTo(1.0d));

        assertThat(processor.starts.get())
                .as("y la tarea no debe ejecutarse dos veces")
                .isEqualTo(1);
        assertThat(queueDepth(JobsTopology.WORK_QUEUE))
                .as("no queda trabajo pendiente")
                .isZero();
        assertThat(queueDepth(JobsTopology.DEAD_QUEUE))
                .as("y nada acabó en la cola muerta: no falló la tarea, se cayó el worker")
                .isZero();
    }

    /**
     * Corta la conexión desde el broker. La API de gestión no vale para esto en
     * esta versión —{@code /api/connections} devuelve lista vacía—, así que se usa
     * {@code rabbitmqctl} dentro del contenedor.
     */
    private static void killWorkerConnection() throws Exception {
        var result = RABBIT.execInContainer("rabbitmqctl", "close_all_connections", "worker muerto");
        assertThat(result.getExitCode())
                .as("si la simulación de la caída no funciona, la prueba no prueba nada")
                .isZero();
    }

    private double taskCounter(String outcome) {
        var counter = meters.find("jobforge.tasks").tag("outcome", outcome).counter();
        return counter == null ? 0.0d : counter.count();
    }

    private long queueDepth(String queue) {
        Long count = rabbitTemplate.execute(channel -> channel.messageCount(queue));
        return count == null ? 0L : count;
    }

    private void publishTask(String taskId) {
        JobTask task = new JobTask("job-1", taskId, 0, "contenido sintético");
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
