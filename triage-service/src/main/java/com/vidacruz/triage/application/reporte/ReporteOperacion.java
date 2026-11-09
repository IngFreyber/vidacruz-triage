package com.vidacruz.triage.application.reporte;

import java.time.Instant;
import java.util.List;

/**
 * Reporte de operación (RF-05) para el rango [desde, hasta).
 *
 * @param desde   inicio del rango (inclusivo)
 * @param hasta   fin del rango (exclusivo)
 * @param resumen métricas del rango completo
 * @param horas   métricas por hora, incluidas las horas sin pacientes
 */
public record ReporteOperacion(Instant desde, Instant hasta, MetricasOperacion resumen, List<MetricasOperacion> horas) {

    public ReporteOperacion {
        horas = List.copyOf(horas);
    }
}
