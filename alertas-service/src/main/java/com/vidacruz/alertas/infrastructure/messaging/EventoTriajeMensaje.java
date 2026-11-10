package com.vidacruz.alertas.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Lectura tolerante del contrato v1 de {@code triaje.eventos} (ADR-0002): solo lo que necesita una alerta.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventoTriajeMensaje(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID pacienteId,
        String nombreCompleto,
        String quejaPrincipal,
        String nivel,
        Long slaMinutos,
        Instant fechaRegistro) {
}
