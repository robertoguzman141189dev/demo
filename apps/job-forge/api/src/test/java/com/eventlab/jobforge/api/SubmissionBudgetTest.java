package com.eventlab.jobforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El cubo de fichas es la única pieza del blindaje con lógica propia, así que es
 * la que se prueba. El rate limiting por IP vive en el ingress y no se puede
 * probar aquí.
 */
class SubmissionBudgetTest {

    private static SubmissionBudget budget(int tasksPerMinute) {
        ApiProperties properties = new ApiProperties();
        properties.setGlobalTasksPerMinute(tasksPerMinute);
        return new SubmissionBudget(properties);
    }

    @Test
    @DisplayName("arranca lleno: el primer visitante no se encuentra la puerta cerrada")
    void arrancaLleno() {
        SubmissionBudget presupuesto = budget(100);

        assertThat(presupuesto.available()).isEqualTo(100);
        assertThat(presupuesto.tryReserve(100)).isEqualTo(100);
    }

    @Test
    @DisplayName("concede del todo o nada, nunca a medias")
    void noConcedeParcialmente() {
        SubmissionBudget presupuesto = budget(10);

        assertThat(presupuesto.tryReserve(8)).isEqualTo(8);
        // Quedan 2 y se piden 5: se rechaza entero en vez de conceder 2. Media
        // subida procesada dejaría al visitante creyendo que se acepto todo.
        assertThat(presupuesto.tryReserve(5)).isZero();
        assertThat(presupuesto.tryReserve(2)).isEqualTo(2);
    }

    @Test
    @DisplayName("agotado el cubo, dice cuánto esperar y nunca cero")
    void informaCuantoEsperar() {
        SubmissionBudget presupuesto = budget(60);
        assertThat(presupuesto.tryReserve(60)).isEqualTo(60);

        Duration espera = presupuesto.retryAfter(60);

        // A 60 por minuto, reponer 60 fichas tarda un minuto. Se comprueba el orden
        // de magnitud y no el valor exacto, que depende del reloj.
        assertThat(espera).isGreaterThan(Duration.ofSeconds(30));
        // Nunca cero: un Retry-After de 0 convierte el rechazo en un bucle.
        assertThat(presupuesto.retryAfter(1)).isPositive();
    }

    @Test
    @DisplayName("el cubo se repone con el tiempo")
    void seRepone() throws InterruptedException {
        // Ritmo alto para que la reposición sea medible sin alargar la prueba:
        // 60 000 por minuto son 1 000 por segundo.
        SubmissionBudget presupuesto = budget(60_000);
        assertThat(presupuesto.tryReserve(60_000)).isEqualTo(60_000);
        assertThat(presupuesto.tryReserve(1)).isZero();

        Thread.sleep(120);

        // En 120 ms deberían haberse repuesto unas 120 fichas. Se pide bastante
        // menos para que la prueba no dependa de la precisión del planificador.
        assertThat(presupuesto.tryReserve(50)).isEqualTo(50);
    }

    @Test
    @DisplayName("no se repone por encima de su capacidad")
    void noDesbordaLaCapacidad() throws InterruptedException {
        SubmissionBudget presupuesto = budget(60_000);

        Thread.sleep(50);

        // Estaba lleno y ha pasado tiempo: sigue lleno, no más que lleno.
        assertThat(presupuesto.available()).isEqualTo(60_000);
    }

    @Test
    @DisplayName("llamadas muy seguidas no pierden el tiempo transcurrido")
    void noPierdeElTiempoEnLlamadasSeguidas() throws InterruptedException {
        // Este es el error que el recuento en milésimas evita: con enteros, cada
        // llamada demasiado seguida repondría cero y el cubo no se rellenaría
        // nunca por muchas veces que se consultara.
        SubmissionBudget presupuesto = budget(600);
        assertThat(presupuesto.tryReserve(600)).isEqualTo(600);

        for (int i = 0; i < 50; i++) {
            presupuesto.available();
        }
        Thread.sleep(250);
        for (int i = 0; i < 50; i++) {
            presupuesto.available();
        }

        // 600 por minuto son 10 por segundo; en 250 ms toca al menos una ficha.
        assertThat(presupuesto.available()).isPositive();
    }
}
