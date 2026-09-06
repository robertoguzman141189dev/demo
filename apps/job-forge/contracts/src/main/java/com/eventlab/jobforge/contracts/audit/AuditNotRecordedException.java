package com.eventlab.jobforge.contracts.audit;

/**
 * Kafka no se hizo cargo del hecho. Quien la reciba no debe acusar el mensaje que
 * estaba procesando: el trabajo sigue siendo suyo y la auditoría no tiene agujero.
 */
public class AuditNotRecordedException extends RuntimeException {

    public AuditNotRecordedException(String message, Throwable cause) {
        super(message, cause);
    }
}
