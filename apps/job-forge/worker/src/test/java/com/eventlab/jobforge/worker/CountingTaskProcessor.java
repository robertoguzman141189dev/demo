package com.eventlab.jobforge.worker;

import com.eventlab.jobforge.contracts.message.JobTask;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Procesador que cuenta cuántas veces ha empezado a trabajar. Sin esto, las
 * pruebas no podrían distinguir "la tarea se completó" de "la tarea se entregó
 * dos veces y se completó en la segunda", que es justo lo que hay que demostrar.
 */
public class CountingTaskProcessor extends TaskProcessor {

    final AtomicInteger starts = new AtomicInteger();
    final CountDownLatch firstStarted = new CountDownLatch(1);

    public CountingTaskProcessor(WorkerProperties properties) {
        super(properties);
    }

    @Override
    public Duration execute(JobTask task) throws InterruptedException {
        starts.incrementAndGet();
        firstStarted.countDown();
        return super.execute(task);
    }
}
