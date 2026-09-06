package com.eventlab.jobforge.contracts.message;

import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.ClassMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Formato de los mensajes: JSON, con el tipo fijado en el código y no en el
 * mensaje.
 *
 * <p>Por defecto, el convertidor de Spring escribe el nombre de la clase Java en
 * el header {@code __TypeId__} y lo usa al deserializar. Aquí no se escribe nada
 * y al leer siempre se asume {@link JobTask}, por tres razones:
 *
 * <ol>
 *   <li>El worker reconstruye el mensaje al republicarlo a un tramo de reintento.
 *       Un header perdido en esa copia rompería la deserialización en el intento
 *       siguiente: un fallo que solo aparece a partir del segundo intento.</li>
 *   <li>El nombre de una clase Java no tiene por qué viajar por el cable, y menos
 *       en un sistema que aspira a ser políglota. Kafka leerá estos mismos datos
 *       en F3.</li>
 *   <li>Deserializar la clase que diga el mensaje es un riesgo conocido, y por eso
 *       Spring solo confía en una lista de paquetes. Fijando el tipo en el código,
 *       el mensaje deja de tener voto sobre qué se instancia.</li>
 * </ol>
 */
@Configuration
public class JobMessageConfiguration {

    @Bean
    public MessageConverter jobMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        converter.setClassMapper(new FixedTypeClassMapper());
        return converter;
    }

    /** Ni escribe el tipo al publicar, ni lo lee al consumir. */
    private static final class FixedTypeClassMapper implements ClassMapper {

        @Override
        public void fromClass(Class<?> clazz, MessageProperties properties) {
            // A propósito, nada: el tipo no viaja.
        }

        @Override
        public Class<?> toClass(MessageProperties properties) {
            return JobTask.class;
        }
    }
}
