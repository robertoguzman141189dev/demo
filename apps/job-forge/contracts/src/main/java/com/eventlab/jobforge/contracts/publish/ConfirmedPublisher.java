package com.eventlab.jobforge.contracts.publish;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Publica y no vuelve hasta que el broker lo confirma.
 *
 * <p>Publicar en AMQP es asíncrono: {@code send()} vuelve enseguida y eso no
 * significa que el mensaje exista en ninguna parte. Sobre esa distinción se
 * apoya todo el diseño de reintentos: el worker solo puede acusar la tarea
 * original <em>después</em> de que el broker confirme la republicación. Si acusa
 * antes y la publicación se pierde, el trabajo desaparece: ya no está en la cola
 * de trabajo y nunca llegó a la de espera.
 *
 * <p>Se comprueban dos cosas distintas que suelen confundirse. La confirmación
 * dice que el broker aceptó el mensaje y —al ser colas quorum— que la mayoría lo
 * escribió a disco. El <em>retorno</em> dice que el mensaje no encajó con ningún
 * binding: llegó al exchange y se habría descartado en silencio. Publicar con el
 * flag {@code mandatory} es lo que convierte ese descarte silencioso en un error
 * visible, y es la defensa contra una routing key mal escrita.
 */
@Component
@EnableConfigurationProperties(PublisherProperties.class)
public class ConfirmedPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final PublisherProperties properties;

    public ConfirmedPublisher(RabbitTemplate rabbitTemplate, PublisherProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    /**
     * @param correlationId identificador para casar la confirmación con el envío;
     *                      se usa el id de la tarea para que los registros se
     *                      puedan seguir de punta a punta
     * @throws MessageNotConfirmedException si el broker no confirma, rechaza, o
     *                                      devuelve el mensaje por no ruteable
     */
    public void publish(String exchange, String routingKey, Message message, String correlationId) {
        CorrelationData correlation = new CorrelationData(correlationId);
        rabbitTemplate.send(exchange, routingKey, message, correlation);

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture()
                    .get(properties.getConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Un apagado ordenado interrumpe el hilo mientras espera. No se traga:
            // se restaura la marca para que el resto del apagado la vea.
            Thread.currentThread().interrupt();
            throw new MessageNotConfirmedException(
                    "interrumpido esperando la confirmación de %s".formatted(correlationId), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new MessageNotConfirmedException(
                    "sin confirmación del broker para %s".formatted(correlationId), e);
        }

        if (confirm == null || !confirm.isAck()) {
            throw new MessageNotConfirmedException("el broker rechazó %s: %s"
                    .formatted(correlationId, confirm == null ? "sin respuesta" : confirm.getReason()));
        }
        if (correlation.getReturned() != null) {
            throw new MessageNotConfirmedException(
                    "mensaje no ruteable: %s con routing key '%s' no llegó a ninguna cola"
                            .formatted(exchange, routingKey));
        }
    }
}
