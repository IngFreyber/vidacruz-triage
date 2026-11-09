package com.vidacruz.triage.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de auditoría clínica (RN-04): timestamp, paciente, estado anterior, estado nuevo y tipo de evento.
 */
public record EntradaAuditoria(
        UUID eventoId,
        UUID pacienteId,
        TipoEvento tipoEvento,
        EstadoPaciente estadoAnterior,
        EstadoPaciente estadoNuevo,
        NivelTriage nivel,
        String detalle,
        Instant ocurridoEn) {
}
