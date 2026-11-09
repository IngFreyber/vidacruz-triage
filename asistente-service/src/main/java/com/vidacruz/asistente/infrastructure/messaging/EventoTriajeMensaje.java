package com.vidacruz.asistente.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Lectura tolerante del contrato v1 de {@code triaje.eventos} (ADR-0002): solo los campos que la vista
 * necesita; los campos desconocidos o nuevos se ignoran para soportar la evolución compatible del esquema.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventoTriajeMensaje(
        UUID eventId,
        String eventType,
        Integer eventVersion,
        Instant occurredAt,
        UUID pacienteId,
        String nivel,
        String estadoNuevo) {
}
