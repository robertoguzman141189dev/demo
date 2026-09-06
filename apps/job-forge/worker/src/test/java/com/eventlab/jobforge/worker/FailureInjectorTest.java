package com.eventlab.jobforge.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FailureInjectorTest {

    /** Semilla fija: una palanca del panel no puede probarse con azar de verdad. */
    private final FailureInjector injector = new FailureInjector(RandomGenerator.of("L64X128MixRandom"));

    @Test
    void al_cero_por_ciento_no_falla_nunca() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(injector.shouldFail(0)).isFalse();
        }
    }

    @Test
    void al_cien_por_ciento_falla_siempre() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(injector.shouldFail(100)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-50, 150, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void un_valor_fuera_de_rango_se_recorta_en_vez_de_reventar(int probability) {
        // Viene de un panel público y anónimo: un parámetro absurdo no puede
        // tumbar al worker ni lanzar una excepción desde dentro del listener.
        assertThat(injector.shouldFail(probability)).isIn(true, false);
    }

    @Test
    void al_cincuenta_por_ciento_la_proporcion_es_creible() {
        int failures = 0;
        for (int i = 0; i < 10_000; i++) {
            if (injector.shouldFail(50)) {
                failures++;
            }
        }
        assertThat(failures).isBetween(4_500, 5_500);
    }
}
