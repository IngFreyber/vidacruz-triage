package com.vidacruz.asistente.domain.urgencias;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Evento de triaje relevante para la vista de urgencias.
 *
 * @param eventId     id único (deduplicación: la entrega es at-least-once)
 * @param tipo        paciente_registrado | paciente_en_consulta | paciente_alta | paciente_escalado
 * @param pacienteId  paciente afectado
 * @param nivel       nivel de triaje
 * @param estadoNuevo estado del paciente tras el evento (REGISTRADO, EN_CONSULTA o ALTA)
 * @param ocurridoEn  instante del hecho (descarta eventos más antiguos que el último aplicado)
 */
public record EventoTriaje(
        UUID eventId,
        String tipo,
        UUID pacienteId,
        NivelTriage nivel,
        String estadoNuevo,
        Instant ocurridoEn) {

    public static final String ESTADO_ALTA = "ALTA";

    public EventoTriaje {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(tipo, "eventType");
        Objects.requireNonNull(pacienteId, "pacienteId");
        Objects.requireNonNull(nivel, "nivel");
        Objects.requireNonNull(estadoNuevo, "estadoNuevo");
        Objects.requireNonNull(ocurridoEn, "occurredAt");
    }

    /** Un paciente está activo en la urgencia mientras no tenga el alta. */
    public boolean dejaActivo() {
        return !ESTADO_ALTA.equals(estadoNuevo);
    }
}
