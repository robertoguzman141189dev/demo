package com.eventlab.jobforge.contracts.audit;

import java.util.Map;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;

/**
 * Los topics, declarados en código igual que la topología de RabbitMQ. Nada de
 * crearlos a mano ni dejar que Kafka los autocree: la autocreación da un topic de
 * una partición con la retención por defecto, que es justo lo contrario de lo que
 * necesita cada uno de estos dos.
 *
 * <p>Ojo con el número de particiones: cambiarlo en un topic con estado por clave
 * <strong>reasigna el hash</strong>. Los eventos nuevos de una tarea pueden acabar
 * en una partición distinta de los viejos y el orden se rompe hacia atrás, sin
 * aviso y sin error.
 */
@Configuration
@EnableConfigurationProperties(AuditTopicsConfiguration.AuditProperties.class)
public class AuditTopicsConfiguration {

    @Bean
    public NewTopic jobEventsTopic(AuditProperties properties) {
        return TopicBuilder.name(AuditTopics.EVENTS)
                .partitions(properties.getPartitions())
                .replicas(properties.getReplicationFactor())
                .configs(Map.of(
                        // La historia caduca: es auditoría, no un archivo eterno.
                        TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE,
                        TopicConfig.RETENTION_MS_CONFIG, String.valueOf(properties.getEventRetentionMs())))
                .build();
    }

    @Bean
    public NewTopic taskStateTopic(AuditProperties properties) {
        return TopicBuilder.name(AuditTopics.STATE)
                .partitions(properties.getPartitions())
                .replicas(properties.getReplicationFactor())
                .configs(Map.of(
                        TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT,

                        // Cuánta basura tolera antes de ponerse a compactar. Bajarlo
                        // compacta antes y gasta más CPU; subirlo deja crecer el topic.
                        TopicConfig.MIN_CLEANABLE_DIRTY_RATIO_CONFIG, "0.1",

                        // El segmento activo nunca se compacta. Sin cerrarlo cada
                        // cierto tiempo, en un topic con poco tráfico la compactación
                        // no llega a ocurrir nunca y el "estado" acumula versiones.
                        TopicConfig.SEGMENT_MS_CONFIG, String.valueOf(properties.getSegmentMs()),

                        // Cuánto sobrevive un tombstone —el borrado de una clave—
                        // antes de desaparecer. Debe dar tiempo a que lo lea hasta el
                        // consumidor más lento, o habrá quien nunca se entere del
                        // borrado y conserve un estado fantasma.
                        TopicConfig.DELETE_RETENTION_MS_CONFIG, String.valueOf(properties.getTombstoneRetentionMs())))
                .build();
    }

    @ConfigurationProperties(prefix = "job-forge.audit")
    public static class AuditProperties {

        private int partitions = 3;

        /** En local hay un solo broker; en el cluster debe ser 3. */
        private short replicationFactor = 1;

        private long eventRetentionMs = 7L * 24 * 60 * 60 * 1000;

        private long segmentMs = 60_000L;

        private long tombstoneRetentionMs = 60_000L;

        public int getPartitions() {
            return partitions;
        }

        public void setPartitions(int partitions) {
            this.partitions = partitions;
        }

        public short getReplicationFactor() {
            return replicationFactor;
        }

        public void setReplicationFactor(short replicationFactor) {
            this.replicationFactor = replicationFactor;
        }

        public long getEventRetentionMs() {
            return eventRetentionMs;
        }

        public void setEventRetentionMs(long eventRetentionMs) {
            this.eventRetentionMs = eventRetentionMs;
        }

        public long getSegmentMs() {
            return segmentMs;
        }

        public void setSegmentMs(long segmentMs) {
            this.segmentMs = segmentMs;
        }

        public long getTombstoneRetentionMs() {
            return tombstoneRetentionMs;
        }

        public void setTombstoneRetentionMs(long tombstoneRetentionMs) {
            this.tombstoneRetentionMs = tombstoneRetentionMs;
        }
    }
}
