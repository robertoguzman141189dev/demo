package com.eventlab.jobforge.worker;

/**
 * No se pudo reconstruir el estado. El worker no arranca: es mejor un pod que
 * falla y se reinicia que uno que consume tareas sin saber cuáles ya se hicieron.
 */
public class StateRebuildFailedException extends RuntimeException {

    public StateRebuildFailedException(String message) {
        super(message);
    }
}
