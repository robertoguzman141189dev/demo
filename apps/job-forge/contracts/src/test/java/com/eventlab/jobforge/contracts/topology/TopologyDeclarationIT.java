package com.eventlab.jobforge.contracts.topology;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Comprueba que la topología que llega al broker es la que creemos haber
 * declarado, <strong>con los valores de producción</strong>: sin tiempos
 * comprimidos ni atajos.
 *
 * <p>Reparto deliberado de responsabilidades con {@code StagedBackoffIT}: aquel
 * demuestra que el mecanismo de espera funciona, con tiempos cortos para que la
 * prueba sea rápida; este demuestra que los números declarados son los que dice
 * la documentación. Sin los dos, una prueba verde no significaría nada: se puede
 * tener un backoff que funcione perfectamente con los tiempos equivocados.
 *
 * <p>Se lee por la API de gestión y no por AMQP porque una declaración pasiva
 * solo dice si la cola existe; los argumentos con los que se creó no viajan por
 * el protocolo.
 */
@Testcontainers
@SpringBootTest
class TopologyDeclarationIT {

    /**
     * La misma etiqueta exacta que corre en {@code local/docker-compose.yml}. Un
     * contenedor por clase de prueba: cada clase declara la topología con
     * parámetros distintos, y redeclarar una cola con argumentos distintos es un
     * PRECONDITION_FAILED, no una actualización silenciosa.
     */
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4.3.5-management-alpine")
                    .asCompatibleSubstituteFor("rabbitmq"));

    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired
    private RabbitAdmin rabbitAdmin;

    /**
     * Arrancar el contexto de Spring no crea nada en el broker: {@code RabbitAdmin}
     * declara la topología cuando se abre la primera conexión AMQP, y la fábrica
     * de conexiones es perezosa. Un servicio que solo publica de vez en cuando
     * tampoco tiene su topología lista hasta el primer mensaje — detalle que
     * importará al escribir la readiness probe del worker en F5.
     */
    @BeforeEach
    void declararTopologia() {
        rabbitAdmin.initialize();
    }

    @Test
    void la_cola_de_trabajo_es_quorum_y_muere_hacia_la_cola_terminal() throws Exception {
        JsonNode args = queueArguments(JobsTopology.WORK_QUEUE);

        assertThat(args.path("x-queue-type").asText()).isEqualTo("quorum");
        assertThat(args.path("x-message-ttl").asLong()).isEqualTo(15 * 60 * 1000L);
        assertThat(args.path("x-delivery-limit").asInt()).isEqualTo(5);
        assertThat(args.path("x-dead-letter-exchange").asText())
                .isEqualTo(JobsTopology.DEAD_LETTER_EXCHANGE);
        assertThat(args.path("x-dead-letter-routing-key").asText())
                .isEqualTo(JobsTopology.DEAD_ROUTING_KEY);
    }

    @Test
    void la_cola_de_trabajo_no_puede_perder_mensajes_al_dead_letterear() throws Exception {
        JsonNode args = queueArguments(JobsTopology.WORK_QUEUE);

        // at-least-once cierra la única ventana de pérdida del diseño, y exige
        // reject-publish para poder aplicarse (ADR 003).
        assertThat(args.path("x-dead-letter-strategy").asText()).isEqualTo("at-least-once");
        assertThat(args.path("x-overflow").asText()).isEqualTo("reject-publish");
    }

    @ParameterizedTest
    @EnumSource(RetryStage.class)
    void cada_tramo_espera_lo_que_dice_su_nombre_y_devuelve_al_principio(RetryStage stage) throws Exception {
        JsonNode args = queueArguments(stage.queueName());

        assertThat(args.path("x-message-ttl").asLong())
                .as("el TTL declarado del tramo %s", stage.queueName())
                .isEqualTo(stage.defaultDelay().toMillis());
        assertThat(args.path("x-dead-letter-exchange").asText())
                .as("al vencer el TTL el mensaje vuelve al exchange de entrada")
                .isEqualTo(JobsTopology.JOBS_EXCHANGE);
        assertThat(args.path("x-dead-letter-routing-key").asText())
                .isEqualTo(JobsTopology.WORK_ROUTING_KEY);
    }

    @ParameterizedTest
    @EnumSource(RetryStage.class)
    void los_tramos_de_espera_no_tienen_consumidores(RetryStage stage) throws Exception {
        // Es la afirmación central del diseño: son temporizadores, no colas de
        // trabajo. Si alguien engancha un consumidor aquí, el backoff desaparece.
        assertThat(queue(stage.queueName()).path("consumers").asInt()).isZero();
    }

    @Test
    void la_cola_terminal_no_reencola_ni_caduca() throws Exception {
        JsonNode args = queueArguments(JobsTopology.DEAD_QUEUE);

        assertThat(args.path("x-queue-type").asText()).isEqualTo("quorum");
        assertThat(args.has("x-dead-letter-exchange"))
                .as("de la cola muerta solo se sale por decisión humana")
                .isFalse();
        assertThat(args.has("x-message-ttl"))
                .as("un trabajo muerto no debe evaporarse antes de que alguien lo mire")
                .isFalse();
    }

    private static JsonNode queueArguments(String name) throws Exception {
        return queue(name).path("arguments");
    }

    private static JsonNode queue(String name) throws Exception {
        String credentials = RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://%s:%d/api/queues/%%2F/%s"
                        .formatted(RABBIT.getHost(), RABBIT.getHttpPort(), name)))
                .header("Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes()))
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("la cola %s debería estar declarada; el broker respondió: %s", name, response.body())
                .isEqualTo(200);
        return JSON.readTree(response.body());
    }
}
