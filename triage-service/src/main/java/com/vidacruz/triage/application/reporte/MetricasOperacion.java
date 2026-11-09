package com.vidacruz.triage.application.reporte;

import com.vidacruz.triage.domain.model.NivelTriage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Métricas de operación de una hora (o del rango completo cuando {@code hora} es {@code null}).
 *
 * @param hora                  inicio de la hora (UTC); {@code null} en la fila de resumen
 * @param totalRegistrados      pacientes registrados en la hora
 * @param porNivel              conteo por nivel de triaje (incluye niveles en 0)
 * @param pacientesAtendidos    registrados en la hora que ya entraron a consulta (base de la espera)
 * @param esperaPromedioMinutos promedio registro → primera entrada a consulta; {@code null} si nadie entró
 * @param esperaP95Minutos      percentil 95 (interpolado) de esa espera; {@code null} si nadie entró
 */
public record MetricasOperacion(
        Instant hora,
        long totalRegistrados,
        Map<NivelTriage, Long> porNivel,
        long pacientesAtendidos,
        BigDecimal esperaPromedioMinutos,
        BigDecimal esperaP95Minutos) {

    public MetricasOperacion {
        // EnumMap conserva el orden de gravedad (CRITICO → NO_URGENTE_2) al serializar.
        porNivel = Collections.unmodifiableMap(new EnumMap<>(porNivel));
    }
}
