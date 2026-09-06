package com.eventlab.jobforge.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * El proyector. No habla con RabbitMQ ni con el visitante: solo lee eventos y
 * deriva estado. Puede pararse, borrarse y volver a arrancar leyendo el log desde
 * el principio, y el resultado será el mismo.
 */
@SpringBootApplication(scanBasePackages = "com.eventlab.jobforge")
public class JobForgeAuditApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobForgeAuditApplication.class, args);
    }
}
