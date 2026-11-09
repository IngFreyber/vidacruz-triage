package com.vidacruz.asistente.domain.urgencias;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Pacientes activos (todos los que no están en ALTA) por nivel de triaje.
 *
 * @param porNivel      conteo por nivel, siempre con los 5 niveles y en orden de gravedad
 * @param actualizadoEn instante del último evento aplicado ({@code null} si aún no llegó ninguno)
 */
public record EstadoUrgencias(Map<NivelTriage, Long> porNivel, Instant actualizadoEn) {

    public EstadoUrgencias {
        EnumMap<NivelTriage, Long> completo = new EnumMap<>(NivelTriage.class);
        for (NivelTriage nivel : NivelTriage.values()) {
            completo.put(nivel, Math.max(0L, porNivel.getOrDefault(nivel, 0L)));
        }
        porNivel = Collections.unmodifiableMap(completo);
    }

    public long totalActivos() {
        return porNivel.values().stream().mapToLong(Long::longValue).sum();
    }
}
