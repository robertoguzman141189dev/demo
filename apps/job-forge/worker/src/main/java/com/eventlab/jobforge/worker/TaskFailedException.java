package com.eventlab.jobforge.worker;

/** La tarea falló por una razón de negocio. Reintentable. */
public class TaskFailedException extends RuntimeException {

    public TaskFailedException(String message) {
        super(message);
    }
}
