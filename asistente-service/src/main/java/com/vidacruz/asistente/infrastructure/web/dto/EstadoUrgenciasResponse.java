package com.vidacruz.asistente.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vidacruz.asistente.domain.urgencias.EstadoUrgencias;
import com.vidacruz.asistente.domain.urgencias.NivelTriage;

import java.time.Instant;
import java.util.Map;

/**
 * Respuesta de {@code GET /asistente/estado-urgencias}.
 *
 * @param totalActivos  pacientes que no están en ALTA
 * @param porNivel      pacientes activos por nivel de triaje (los 5 niveles, en orden de gravedad)
 * @param actualizadoEn instante del último evento aplicado ({@code null} si la vista está vacía)
 * @param fuente        de dónde se reconstruye la vista
 */
public record EstadoUrgenciasResponse(
        long totalActivos,
        Map<NivelTriage, Long> porNivel,
        @JsonInclude(JsonInclude.Include.ALWAYS) Instant actualizadoEn,
        String fuente) {

    public static EstadoUrgenciasResponse desde(EstadoUrgencias e) {
        return new EstadoUrgenciasResponse(e.totalActivos(), e.porNivel(), e.actualizadoEn(),
                "kafka:triaje.eventos");
    }
}
