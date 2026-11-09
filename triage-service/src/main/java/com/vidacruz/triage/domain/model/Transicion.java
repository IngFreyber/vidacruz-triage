package com.vidacruz.triage.domain.model;

/**
 * Resultado de una operación sobre el agregado: el nuevo estado del paciente y el evento que la describe.
 */
public record Transicion(Paciente paciente, EventoPaciente evento) {
}
