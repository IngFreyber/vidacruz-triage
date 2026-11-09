package com.vidacruz.triage.domain.service;

import com.vidacruz.triage.domain.model.NivelTriage;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * SLA por nivel (RN-02): tiempo máximo desde el registro hasta entrar a consulta.
 * Configurable para poder probar el escalamiento sin esperar decenas de minutos (RF-03).
 */
public final class PoliticaSla {

    private final Map<NivelTriage, Duration> slaPorNivel;

    public PoliticaSla(Map<NivelTriage, Duration> slaPorNivel) {
        EnumMap<NivelTriage, Duration> copia = new EnumMap<>(NivelTriage.class);
        copia.putAll(slaPorNivel);
        for (NivelTriage nivel : NivelTriage.values()) {
            Duration d = copia.get(nivel);
            if (d == null || d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("SLA inválido o ausente para el nivel " + nivel);
            }
        }
        this.slaPorNivel = copia;
    }

    public static PoliticaSla porDefecto() {
        return new PoliticaSla(Map.of(
                NivelTriage.CRITICO, Duration.ofMinutes(5),
                NivelTriage.URGENTE, Duration.ofMinutes(10),
                NivelTriage.URGENTE_2, Duration.ofMinutes(30),
                NivelTriage.NO_URGENTE, Duration.ofMinutes(60),
                NivelTriage.NO_URGENTE_2, Duration.ofMinutes(120)));
    }

    public Duration slaDe(NivelTriage nivel) {
        return slaPorNivel.get(nivel);
    }
}
