package com.eventlab.jobforge.contracts.topology;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * La topología completa de job-forge, declarada en código.
 *
 * <p>Nada de definiciones importadas al arrancar el broker: un servicio que
 * declara lo que necesita puede desplegarse sobre un RabbitMQ vacío y la
 * topología es idéntica en tu portátil, en kind y en EKS. Las declaraciones son
 * idempotentes mientras los argumentos no cambien; si cambian, el broker rechaza
 * la redeclaración con {@code PRECONDITION_FAILED} en vez de mutar la cola por
 * detrás. Cambiar un TTL exige borrar la cola a propósito.
 *
 * <p>El camino completo de un mensaje está en {@code docs/adr/003}.
 */
@Configuration
@EnableConfigurationProperties(JobTopologyProperties.class)
public class JobTopologyConfiguration {

    /**
     * Estrategia de dead-lettering de las colas quorum.
     *
     * <p>Con {@code at-most-once} —el valor por defecto— el mensaje se envía al
     * dead letter exchange sin esperar confirmación, y si el broker cae en ese
     * instante desaparece. Sería el único punto de todo el diseño donde un
     * trabajo puede perderse del todo. Con {@code at-least-once} el traspaso se
     * confirma, a cambio de poder duplicarlo: aceptable porque los consumidores
     * de job-forge son idempotentes por obligación.
     */
    private static final String AT_LEAST_ONCE = "at-least-once";

    /** Exigido por {@code at-least-once}: la cola rechaza publicaciones antes que descartar. */
    private static final String REJECT_PUBLISH = "reject-publish";

    @Bean
    public Declarables jobForgeTopology(JobTopologyProperties properties) {
        List<Declarable> declarables = new ArrayList<>();

        DirectExchange jobs = directExchange(JobsTopology.JOBS_EXCHANGE);
        DirectExchange retry = directExchange(JobsTopology.RETRY_EXCHANGE);
        DirectExchange dlx = directExchange(JobsTopology.DEAD_LETTER_EXCHANGE);
        declarables.add(jobs);
        declarables.add(retry);
        declarables.add(dlx);

        // La única cola con consumidores. Todo lo que muere aquí —TTL vencido o
        // límite de entregas superado— va directo a la cola terminal.
        Queue work = QueueBuilder.durable(JobsTopology.WORK_QUEUE)
                .quorum()
                .withArgument("x-message-ttl", properties.getWorkTtl().toMillis())
                .withArgument("x-delivery-limit", properties.getDeliveryLimit())
                .withArgument("x-dead-letter-exchange", JobsTopology.DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", JobsTopology.DEAD_ROUTING_KEY)
                .withArgument("x-dead-letter-strategy", AT_LEAST_ONCE)
                .withArgument("x-overflow", REJECT_PUBLISH)
                .build();
        declarables.add(work);
        declarables.add(BindingBuilder.bind(work).to(jobs).with(JobsTopology.WORK_ROUTING_KEY));

        // Los tramos de espera. Ninguno tiene consumidor: son temporizadores.
        for (RetryStage stage : RetryStage.values()) {
            Queue waitQueue = QueueBuilder.durable(stage.queueName())
                    .quorum()
                    .withArgument("x-message-ttl", properties.delayOf(stage).toMillis())
                    // Al vencer el TTL, de vuelta al principio. Aquí se cierra el
                    // ciclo; la condición de salida es el contador x-attempt del
                    // worker, no el broker. Sin ella, esto es un bucle infinito.
                    .withArgument("x-dead-letter-exchange", JobsTopology.JOBS_EXCHANGE)
                    .withArgument("x-dead-letter-routing-key", JobsTopology.WORK_ROUTING_KEY)
                    .withArgument("x-dead-letter-strategy", AT_LEAST_ONCE)
                    .withArgument("x-overflow", REJECT_PUBLISH)
                    .build();
            declarables.add(waitQueue);
            declarables.add(BindingBuilder.bind(waitQueue).to(retry).with(stage.routingKey()));
        }

        // Terminal: sin TTL y sin dead letter exchange. De aquí solo se sale por
        // decisión humana desde el panel.
        Queue dead = QueueBuilder.durable(JobsTopology.DEAD_QUEUE)
                .quorum()
                .build();
        declarables.add(dead);
        declarables.add(BindingBuilder.bind(dead).to(dlx).with(JobsTopology.DEAD_ROUTING_KEY));

        return new Declarables(declarables);
    }

    private static DirectExchange directExchange(String name) {
        return ExchangeBuilder.directExchange(name).durable(true).build();
    }
}
