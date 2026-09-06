package com.eventlab.jobforge.worker;

import com.eventlab.jobforge.contracts.message.JobTask;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * El trabajo en sí: una espera con variación aleatoria.
 *
 * <p>Es a propósito. En este demo el trabajo es un pretexto y la mecánica de
 * entrega es el producto; un procesamiento sofisticado solo competiría por la
 * atención de quien lo lee. Lo único que importa es que las tareas duren cosas
 * distintas, porque con duraciones idénticas el reparto parece perfecto siempre y
 * el efecto del prefetch se vuelve invisible.
 */
@Component
public class TaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(TaskProcessor.class);

    private final WorkerProperties properties;
    private final RandomGenerator random;

    // ThreadLocalRandom por el mismo motivo que en FailureInjector: el algoritmo
    // que devuelve RandomGenerator.getDefault() vive en el módulo jdk.random, que
    // el JRE de la imagen no trae, y además no es seguro entre hilos.
    @Autowired
    public TaskProcessor(WorkerProperties properties) {
        this(properties, ThreadLocalRandom.current());
    }

    TaskProcessor(WorkerProperties properties, RandomGenerator random) {
        this.properties = properties;
        this.random = random;
    }

    /**
     * @throws InterruptedException si llega la señal de apagado a media tarea. No
     *                              se traga: quien llame debe dejar el mensaje sin
     *                              acusar para que otro worker lo recoja.
     */
    public Duration execute(JobTask task) throws InterruptedException {
        Duration duration = properties.getTaskDuration();
        long jitterMillis = properties.getTaskJitter().toMillis();
        if (jitterMillis > 0) {
            duration = duration.plusMillis(random.nextLong(jitterMillis));
        }

        log.debug("procesando tarea {} del trabajo {} durante {} ms",
                task.taskId(), task.jobId(), duration.toMillis());
        Thread.sleep(duration);
        return duration;
    }
}
