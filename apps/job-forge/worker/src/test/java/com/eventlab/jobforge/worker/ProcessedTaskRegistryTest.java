package com.eventlab.jobforge.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ProcessedTaskRegistryTest {

    private static final Instant START = Instant.parse("2026-09-04T10:00:00Z");

    @Test
    void una_tarea_completada_se_reconoce_como_duplicada() {
        ProcessedTaskRegistry registry = registry(new WorkerProperties(), Clock.fixed(START, ZoneOffset.UTC));

        assertThat(registry.alreadyProcessed("task-1")).isFalse();
        registry.markProcessed("task-1");

        assertThat(registry.alreadyProcessed("task-1")).isTrue();
    }

    @Test
    void pasada_la_ventana_se_olvida_y_el_duplicado_volveria_a_procesarse() {
        WorkerProperties properties = new WorkerProperties();
        properties.setDeduplicationWindow(Duration.ofMinutes(30));
        MutableClock clock = new MutableClock(START);
        ProcessedTaskRegistry registry = registry(properties, clock);

        registry.markProcessed("task-1");
        clock.advance(Duration.ofMinutes(31));

        // Es la limitación honesta del registro en memoria, no un bug: se prueba
        // para que quede escrita y para que nadie la descubra en producción.
        assertThat(registry.alreadyProcessed("task-1")).isFalse();
    }

    @Test
    void el_registro_no_crece_sin_limite() {
        WorkerProperties properties = new WorkerProperties();
        properties.setDeduplicationCapacity(100);
        ProcessedTaskRegistry registry = registry(properties, Clock.fixed(START, ZoneOffset.UTC));

        for (int i = 0; i < 500; i++) {
            registry.markProcessed("task-" + i);
        }

        assertThat(registry.size())
                .as("un caché sin tope es una fuga de memoria con otro nombre")
                .isEqualTo(100);
        assertThat(registry.alreadyProcessed("task-499")).isTrue();
        assertThat(registry.alreadyProcessed("task-0")).isFalse();
    }

    private static ProcessedTaskRegistry registry(WorkerProperties properties, Clock clock) {
        return new ProcessedTaskRegistry(properties, clock);
    }

    /** Reloj movible: la alternativa sería dormir media hora en la prueba. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
