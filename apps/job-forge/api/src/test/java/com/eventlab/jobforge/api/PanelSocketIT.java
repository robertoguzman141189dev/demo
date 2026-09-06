package com.eventlab.jobforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * El canal en vivo del panel, de punta a punta: un navegador se conecta, alguien
 * encola trabajo y el panel se entera sin preguntar.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "job-forge.panel.snapshot-interval=300ms")
class PanelSocketIT {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4.3.5-management-alpine")
                    .asCompatibleSubstituteFor("rabbitmq"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        KafkaSupport.register(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void el_panel_recibe_la_foto_de_las_colas_y_los_hechos_segun_ocurren() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        WebSocketSession session = openPanel(received);

        try {
            // Las instantáneas llegan solas, sin que nadie las pida: es lo que
            // distingue un socket de estar preguntando cada segundo.
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(received).anyMatch(m -> m.contains("\"type\":\"snapshot\"")));

            assertThat(received.stream().filter(m -> m.contains("snapshot")).findFirst().orElseThrow())
                    .as("la foto incluye el camino completo de una tarea")
                    .contains("jobs.work")
                    .contains("jobs.retry.5s")
                    .contains("jobs.dead");

            rest.postForEntity("/api/jobs/synthetic?tasks=2&failProbability=0", null, String.class);

            // Y los hechos llegan por el mismo canal, leídos del log de auditoría.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(received).anyMatch(m -> m.contains("\"type\":\"event\"")
                            && m.contains("SUBMITTED")));
        } finally {
            session.close(CloseStatus.NORMAL);
        }
    }

    @Test
    void el_canal_es_de_una_sola_direccion() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        WebSocketSession session = openPanel(received);

        try {
            // Lo que mande el cliente se ignora: las acciones van por HTTP, donde se
            // pueden limitar y auditar. Que la sesión siga viva tras esto es la
            // afirmación: no se procesa, pero tampoco tumba nada.
            session.sendMessage(new TextMessage("{\"borra\":\"todo\"}"));
            Thread.sleep(500);

            assertThat(session.isOpen()).isTrue();
            assertThat(received).noneMatch(m -> m.contains("borra"));
        } finally {
            session.close(CloseStatus.NORMAL);
        }
    }

    private WebSocketSession openPanel(List<String> sink) throws Exception {
        return new StandardWebSocketClient()
                .execute(new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                        sink.add(message.getPayload());
                    }
                }, URI.create("ws://localhost:%d/ws/panel".formatted(port)).toString())
                .get();
    }
}
