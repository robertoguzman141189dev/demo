package com.eventlab.jobforge.contracts;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * {@code contracts} es una librería y no tiene clase de arranque propia. Esta
 * existe solo para que las pruebas de integración puedan levantar un contexto de
 * Spring que declare la topología, igual que hará el worker en F2.
 */
@SpringBootApplication
public class TopologyTestApplication {
}
