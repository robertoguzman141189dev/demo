package com.eventlab.jobforge.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * El worker escala horizontalmente: varias réplicas leen de la misma cola y el
 * broker reparte. Nada de su estado sobrevive al proceso, salvo el registro de
 * tareas ya procesadas, que es un caché y se comporta como tal.
 *
 * <p>El escaneo sube hasta {@code com.eventlab.jobforge} para incluir el módulo
 * {@code contracts}, que trae la topología y el publicador con confirmación.
 */
@SpringBootApplication(scanBasePackages = "com.eventlab.jobforge")
public class JobForgeWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobForgeWorkerApplication.class, args);
    }
}
