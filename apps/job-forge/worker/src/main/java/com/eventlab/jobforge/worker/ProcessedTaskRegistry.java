package com.eventlab.jobforge.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Registro de tareas ya completadas. Es lo que hace idempotente al worker.
 *
 * <p>At-least-once no es un caso raro: un worker que muere después de trabajar y
 * antes de acusar provoca que la misma tarea se entregue otra vez. Antes de
 * trabajar se pregunta aquí; al terminar, se anota.
 *
 * <p><strong>Limitación deliberada y visible:</strong> esto vive en la memoria del
 * proceso. Si el worker se reinicia, olvida, y un duplicado que llegue después se
 * procesará de nuevo. Tampoco se comparte entre réplicas, así que solo protege
 * frente a duplicados que caigan en la misma. Es suficiente para el caso real
 * —la reentrega tras un fallo suele volver al mismo pool en cuestión de
 * segundos— y deja de serlo en F3, cuando el estado se reconstruya desde el topic
 * compactado de Kafka. Prometer más que esto con un mapa en memoria sería mentir.
 */
@Component
public class ProcessedTaskRegistry {

    private final Clock clock;
    private final Duration window;
    private final Map<String, Instant> completedAt;

    // Con dos constructores y ninguno marcado, Spring no elige: exige uno sin
    // argumentos y falla al arrancar. El otro existe solo para las pruebas.
    @Autowired
    public ProcessedTaskRegistry(WorkerProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ProcessedTaskRegistry(WorkerProperties properties, Clock clock) {
        this.clock = clock;
        this.window = properties.getDeduplicationWindow();
        int capacity = properties.getDeduplicationCapacity();
        // LRU acotado: sin tope, un worker de larga vida acaba siendo una fuga de
        // memoria con forma de caché.
        this.completedAt = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
                return size() > capacity;
            }
        });
    }

    public boolean alreadyProcessed(String taskId) {
        Instant completed = completedAt.get(taskId);
        if (completed == null) {
            return false;
        }
        if (Duration.between(completed, clock.instant()).compareTo(window) > 0) {
            completedAt.remove(taskId);
            return false;
        }
        return true;
    }

    public void markProcessed(String taskId) {
        completedAt.put(taskId, clock.instant());
    }

    public int size() {
        return completedAt.size();
    }

    /**
     * Olvida todo, como haría un reinicio del proceso. Existe para que las pruebas
     * puedan simular una réplica recién arrancada sin matar la JVM.
     */
    void clear() {
        completedAt.clear();
    }
}
