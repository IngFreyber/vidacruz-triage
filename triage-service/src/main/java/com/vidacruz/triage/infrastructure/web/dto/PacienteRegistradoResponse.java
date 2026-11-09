package com.vidacruz.triage.infrastructure.web.dto;

import com.vidacruz.triage.domain.model.EstadoPaciente;
import com.vidacruz.triage.domain.model.NivelTriage;
import com.vidacruz.triage.domain.model.Paciente;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Respuesta de RF-01: id, nivel, slaMinutos, estado y fechaRegistro (ISO 8601), más los criterios que
 * justifican la clasificación.
 */
public record PacienteRegistradoResponse(
        UUID id,
        NivelTriage nivel,
        long slaMinutos,
        EstadoPaciente estado,
        Instant fechaRegistro,
        List<String> criteriosClasificacion) {

    public static PacienteRegistradoResponse desde(Paciente p) {
        return new PacienteRegistradoResponse(p.id(), p.nivel(), p.slaMinutos(), p.estado(),
                p.fechaRegistro(), p.criteriosClasificacion());
    }
}
