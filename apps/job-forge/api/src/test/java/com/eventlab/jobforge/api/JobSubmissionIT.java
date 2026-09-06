package com.eventlab.jobforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventlab.jobforge.contracts.message.JobHeaders;
import com.eventlab.jobforge.contracts.topology.JobsTopology;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * La ingesta: de archivo subido a tareas confirmadas por el broker.
 */
@Testcontainers
@SpringBootTest(properties = "job-forge.api.max-tasks-per-job=5")
@AutoConfigureMockMvc
class JobSubmissionIT {

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
    private MockMvc mockMvc;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void prepararTopologia() {
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(JobsTopology.WORK_QUEUE);
    }

    @Test
    void cada_linea_del_archivo_se_convierte_en_una_tarea_publicada() throws Exception {
        mockMvc.perform(multipart("/api/jobs")
                        .file(file("primera\nsegunda\n\ntercera\n"))
                        .param("failProbability", "40"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.tasks").value(3))
                .andExpect(jsonPath("$.jobId").isNotEmpty());

        Message first = rabbitTemplate.receive(JobsTopology.WORK_QUEUE, 5_000);
        assertThat(first).as("la respuesta solo llega si el broker confirmó las tareas").isNotNull();

        Integer attempt = first.getMessageProperties().getHeader(JobHeaders.ATTEMPT);
        Integer failProbability = first.getMessageProperties().getHeader(JobHeaders.FAIL_PROBABILITY);
        String taskId = first.getMessageProperties().getHeader(JobHeaders.TASK_ID);

        assertThat(attempt).as("una tarea nueva empieza con el contador a cero").isZero();
        assertThat(failProbability)
                .as("la palanca del panel viaja estampada en cada tarea, no en el worker")
                .isEqualTo(40);
        assertThat(taskId).isNotBlank();
        assertThat(new String(first.getBody(), StandardCharsets.UTF_8))
                .as("las líneas en blanco no generan trabajo")
                .contains("primera");
    }

    @Test
    void un_archivo_demasiado_grande_se_rechaza_en_vez_de_recortarse() throws Exception {
        String content = "linea\n".repeat(6);

        mockMvc.perform(multipart("/api/jobs").file(file(content)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("máximo por subida es 5")));

        // Recortar en silencio dejaría al visitante creyendo que se procesó todo.
        assertThat(rabbitTemplate.receive(JobsTopology.WORK_QUEUE, 500))
                .as("un trabajo rechazado no publica ni una tarea")
                .isNull();
    }

    @Test
    void un_archivo_sin_contenido_util_se_rechaza() throws Exception {
        mockMvc.perform(multipart("/api/jobs").file(file("\n\n   \n")))
                .andExpect(status().isUnprocessableEntity());
    }

    private static MockMultipartFile file(String content) {
        return new MockMultipartFile("file", "tareas.txt", "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
