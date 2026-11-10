package com.vidacruz.alertas.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Hecho {@code paciente_escalado} recibido de {@code triaje.eventos}: todo lo necesario para alertar sin
 * consultar la API de triaje (event-carried state transfer, ADR-0002).
 */
public record EscalamientoPaciente(
        UUID eventId,
        UUID pacienteId,
        String nombreCompleto,
        String quejaPrincipal,
        String nivel,
        long slaMinutos,
        Instant fechaRegistro,
        Instant escaladoEn) {

    public EscalamientoPaciente {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(pacienteId, "pacienteId");
        Objects.requireNonNull(nivel, "nivel");
        Objects.requireNonNull(fechaRegistro, "fechaRegistro");
        Objects.requireNonNull(escaladoEn, "occurredAt");
    }
}
