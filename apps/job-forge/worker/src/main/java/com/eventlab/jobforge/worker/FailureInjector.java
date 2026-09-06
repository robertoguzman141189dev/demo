package com.eventlab.jobforge.worker;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * La palanca del panel. Decide si una tarea "falla" según la probabilidad que
 * viene estampada en el propio mensaje.
 *
 * <p>Que el valor viaje en el mensaje y no en la configuración del worker es lo
 * que hace que el demo siga diciendo la verdad al escalar: con veinte réplicas,
 * un endpoint de control solo alcanzaría a una.
 */
@Component
public class FailureInjector {

    private final RandomGenerator random;

    /**
     * ThreadLocalRandom y no {@code RandomGenerator.getDefault()} por dos motivos,
     * los dos descubiertos empaquetando en F5:
     *
     * <p>El algoritmo por defecto vive en el módulo {@code jdk.random}, que el JRE
     * de Temurin no incluye. La aplicación arrancaba en tu máquina, donde hay un
     * JDK completo, y moría en la imagen con un error que no menciona ni módulos
     * ni empaquetado.
     *
     * <p>Y ese generador por defecto no es seguro entre hilos. Hoy da igual porque
     * la concurrencia del worker es 1, pero es una mina para el día que se suba.
     */
    public FailureInjector() {
        this(ThreadLocalRandom.current());
    }

    FailureInjector(RandomGenerator random) {
        this.random = random;
    }

    /**
     * @param probability porcentaje de 0 a 100. Fuera de rango se recorta en vez de
     *                    reventar: viene de un panel público y anónimo, y un
     *                    parámetro malicioso no debe tumbar al worker
     */
    public boolean shouldFail(int probability) {
        int bounded = Math.clamp(probability, 0, 100);
        if (bounded == 0) {
            return false;
        }
        return random.nextInt(100) < bounded;
    }
}
