package com.vidacruz.triage.domain.exception;

import java.util.UUID;

/**
 * El paciente solicitado no existe (HTTP 404).
 */
public class PacienteNoEncontradoException extends DomainException {

    public PacienteNoEncontradoException(UUID id) {
        super("No existe un paciente con id " + id);
    }
}
