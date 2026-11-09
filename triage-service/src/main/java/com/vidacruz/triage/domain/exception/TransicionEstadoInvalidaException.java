package com.vidacruz.triage.domain.exception;

import com.vidacruz.triage.domain.model.EstadoPaciente;

import java.util.UUID;

/**
 * Transición de estado no permitida por la máquina de estados (RF-02 → HTTP 409).
 */
public class TransicionEstadoInvalidaException extends DomainException {

    private final EstadoPaciente actual;
    private final EstadoPaciente destino;

    public TransicionEstadoInvalidaException(UUID pacienteId, EstadoPaciente actual, EstadoPaciente destino) {
        super("Transición inválida para el paciente %s: %s → %s. Transiciones permitidas desde %s: %s"
                .formatted(pacienteId, actual, destino, actual,
                        actual.transicionesPermitidas().isEmpty() ? "ninguna (estado final)"
                                : actual.transicionesPermitidas()));
        this.actual = actual;
        this.destino = destino;
    }

    public EstadoPaciente actual() {
        return actual;
    }

    public EstadoPaciente destino() {
        return destino;
    }
}
