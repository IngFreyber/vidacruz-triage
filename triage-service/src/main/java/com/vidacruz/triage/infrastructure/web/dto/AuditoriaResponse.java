package com.vidacruz.triage.infrastructure.web.dto;

import com.vidacruz.triage.domain.model.EntradaAuditoria;
import com.vidacruz.triage.domain.model.EstadoPaciente;
import com.vidacruz.triage.domain.model.NivelTriage;

import java.time.Instant;
import java.util.UUID;

/**
 * Entrada de auditoría clínica expuesta en solo lectura (RN-04).
 */
public record AuditoriaResponse(
        UUID eventoId,
        UUID pacienteId,
        String tipoEvento,
        EstadoPaciente estadoAnterior,
        EstadoPaciente estadoNuevo,
        NivelTriage nivel,
        String detalle,
        Instant timestamp) {

    public static AuditoriaResponse desde(EntradaAuditoria e) {
        return new AuditoriaResponse(e.eventoId(), e.pacienteId(), e.tipoEvento().codigo(), e.estadoAnterior(),
                e.estadoNuevo(), e.nivel(), e.detalle(), e.ocurridoEn());
    }
}
