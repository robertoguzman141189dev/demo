package com.eventlab.jobforge.contracts.audit;

import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Cómo escribe job-forge en Kafka. Está aquí, en el módulo de contratos, para que
 * el API, el worker y el proyector no puedan divergir en las garantías.
 */
@Configuration
public class KafkaAuditConfiguration {

    @Bean
    public ProducerFactory<String, Object> auditProducerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildProducerProperties(null);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Sin el nombre de la clase Java en un header, igual que en AMQP: el tipo se
        // fija al leer. Un log de auditoría que solo sabe leer Java no es un log.
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        // El líder no confirma hasta que las réplicas sincronizadas han escrito. Con
        // acks=1 se pierden eventos ya confirmados cuando cae el líder, y lo peor es
        // que nadie se entera.
        config.put(ProducerConfig.ACKS_CONFIG, "all");

        // Numera los envíos por partición, de modo que un reintento del propio
        // cliente —que ocurre solo, ante un timeout— no duplique el mensaje. No es
        // lo mismo que transacciones: no da atomicidad con nada de fuera de Kafka.
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> auditKafkaTemplate(ProducerFactory<String, Object> factory) {
        return new KafkaTemplate<>(factory);
    }
}
