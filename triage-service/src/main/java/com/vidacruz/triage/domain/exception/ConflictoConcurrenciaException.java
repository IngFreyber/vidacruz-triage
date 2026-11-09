package com.vidacruz.triage.domain.exception;

import java.util.UUID;

/**
 * El paciente fue modificado concurrentemente (p. ej. un cambio de estado y el escalamiento a la vez).
 * Se reintenta automáticamente; si persiste se responde HTTP 409.
 */
public class ConflictoConcurrenciaException extends DomainException {

    public ConflictoConcurrenciaException(UUID id) {
        super("El paciente " + id + " fue modificado concurrentemente; reintente la operación");
    }
}
