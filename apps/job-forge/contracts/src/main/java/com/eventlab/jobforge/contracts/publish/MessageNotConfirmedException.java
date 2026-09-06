package com.eventlab.jobforge.contracts.publish;

import org.springframework.amqp.AmqpException;

/**
 * El broker no se hizo cargo del mensaje. Quien la reciba <strong>no debe
 * acusar</strong> el mensaje que estaba procesando: es la única señal de que el
 * trabajo sigue siendo suyo.
 */
public class MessageNotConfirmedException extends AmqpException {

    public MessageNotConfirmedException(String message) {
        super(message);
    }

    public MessageNotConfirmedException(String message, Throwable cause) {
        super(message, cause);
    }
}
