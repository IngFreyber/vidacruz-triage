package com.vidacruz.triage.application.port.out;

import com.vidacruz.triage.application.reporte.MetricasOperacion;
import reactor.core.publisher.Flux;

import java.time.Instant;

/**
 * Puerto del reporte de operación (RF-05). Las agregaciones se calculan íntegramente en la base de datos.
 */
public interface ReporteOperacionRepository {

    /**
     * Emite una fila por hora del rango [desde, hasta) en orden cronológico y, al final, la fila de resumen
     * del rango completo ({@code hora == null}).
     */
    Flux<MetricasOperacion> metricasPorHora(Instant desde, Instant hasta);
}
