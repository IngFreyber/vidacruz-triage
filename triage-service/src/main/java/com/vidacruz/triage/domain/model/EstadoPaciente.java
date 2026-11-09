package com.vidacruz.triage.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estados clínicos del paciente (RF-02) y sus transiciones válidas:
 * REGISTRADO → EN_CONSULTA, REGISTRADO → ALTA, EN_CONSULTA → ALTA.
 */
public enum EstadoPaciente {
    REGISTRADO,
    EN_CONSULTA,
    ALTA;

    public Set<EstadoPaciente> transicionesPermitidas() {
        return switch (this) {
            case REGISTRADO -> EnumSet.of(EN_CONSULTA, ALTA);
            case EN_CONSULTA -> EnumSet.of(ALTA);
            case ALTA -> EnumSet.noneOf(EstadoPaciente.class);
        };
    }

    public boolean puedeTransicionarA(EstadoPaciente destino) {
        return transicionesPermitidas().contains(destino);
    }

    public boolean esActivo() {
        return this != ALTA;
    }
}
