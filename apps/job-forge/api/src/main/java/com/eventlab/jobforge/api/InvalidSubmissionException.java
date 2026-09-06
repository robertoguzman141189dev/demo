package com.eventlab.jobforge.api;

/** Lo que subió el visitante no sirve. Culpa del cliente, no del sistema. */
public class InvalidSubmissionException extends RuntimeException {

    public InvalidSubmissionException(String message) {
        super(message);
    }
}
