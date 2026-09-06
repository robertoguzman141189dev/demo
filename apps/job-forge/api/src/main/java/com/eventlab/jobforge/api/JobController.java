package com.eventlab.jobforge.api;

import com.eventlab.jobforge.contracts.message.JobPriority;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * La superficie que operará el panel en F4. Sin autenticación, como pide el
 * diseño: todo lo que hay al otro lado son datos sintéticos y colas acotadas.
 */
@RestController
@RequestMapping("/api")
public class JobController {

    private final JobSubmissionService submissions;
    private final DeadLetterService deadLetters;

    public JobController(JobSubmissionService submissions, DeadLetterService deadLetters) {
        this.submissions = submissions;
        this.deadLetters = deadLetters;
    }

    /**
     * Sube un archivo y lo convierte en tareas. El archivo no se almacena: se lee,
     * se parte y se descarta antes de responder.
     *
     * @param failProbability palanca del panel, de 0 a 100. Viaja estampada en cada
     *                        tarea para que todas las réplicas del worker apliquen
     *                        la misma condición
     */
    @PostMapping("/jobs")
    public ResponseEntity<JobAccepted> submit(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "NORMAL") JobPriority priority,
            @RequestParam(defaultValue = "0") int failProbability) throws IOException {

        if (file.isEmpty()) {
            throw new InvalidSubmissionException("no llegó ningún archivo");
        }
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return ResponseEntity.accepted()
                .body(submissions.submit(content, priority, Math.clamp(failProbability, 0, 100)));
    }

    /** Variante sin archivo, para el generador de carga del panel. */
    @PostMapping("/jobs/synthetic")
    public ResponseEntity<JobAccepted> submitSynthetic(
            @RequestParam(defaultValue = "10") int tasks,
            @RequestParam(defaultValue = "NORMAL") JobPriority priority,
            @RequestParam(defaultValue = "0") int failProbability) {

        String content = IntStream.rangeClosed(1, Math.clamp(tasks, 1, 200))
                .mapToObj("tarea sintética %d"::formatted)
                .reduce((a, b) -> a + "\n" + b)
                .orElseThrow();
        return ResponseEntity.accepted()
                .body(submissions.submit(content, priority, Math.clamp(failProbability, 0, 100)));
    }

    @GetMapping("/dead-letters")
    public List<DeadLetterView> peekDeadLetters(@RequestParam(defaultValue = "10") int limit) {
        return deadLetters.peek(limit);
    }

    @PostMapping("/dead-letters/reprocess")
    public ReprocessResult reprocessDeadLetters(@RequestParam(defaultValue = "10") int limit) {
        return new ReprocessResult(deadLetters.reprocess(limit));
    }

    @ExceptionHandler(InvalidSubmissionException.class)
    public ResponseEntity<ApiError> onInvalidSubmission(InvalidSubmissionException e) {
        return ResponseEntity.unprocessableEntity().body(new ApiError(e.getMessage()));
    }

    public record ReprocessResult(int reprocessed) {
    }

    public record ApiError(String message) {
    }
}
