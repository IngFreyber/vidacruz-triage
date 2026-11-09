package com.vidacruz.triage.application.reporte;

import com.vidacruz.triage.application.port.out.ReporteOperacionRepository;
import com.vidacruz.triage.domain.exception.ParametrosInvalidosException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * RF-05: reporte de operación por hora. Valida el rango y arma el resultado; los agregados (conteos,
 * promedio y p95) los calcula PostgreSQL, nunca se itera en memoria sobre los pacientes.
 */
public class ReporteOperacionService {

    private final ReporteOperacionRepository repositorio;
    private final Duration rangoMaximo;

    public ReporteOperacionService(ReporteOperacionRepository repositorio, Duration rangoMaximo) {
        this.repositorio = repositorio;
        this.rangoMaximo = rangoMaximo;
    }

    public Mono<ReporteOperacion> generar(Instant desde, Instant hasta) {
        return Mono.fromRunnable(() -> validar(desde, hasta))
                .thenMany(repositorio.metricasPorHora(desde, hasta))
                .collectList()
                .map(filas -> armar(desde, hasta, filas));
    }

    private void validar(Instant desde, Instant hasta) {
        if (!desde.isBefore(hasta)) {
            throw new ParametrosInvalidosException("hasta", "debe ser posterior a 'desde' (%s); recibido %s"
                    .formatted(desde, hasta));
        }
        if (Duration.between(desde, hasta).compareTo(rangoMaximo) > 0) {
            throw new ParametrosInvalidosException("hasta", "el rango no puede superar %d días"
                    .formatted(rangoMaximo.toDays()));
        }
    }

    private static ReporteOperacion armar(Instant desde, Instant hasta, List<MetricasOperacion> filas) {
        List<MetricasOperacion> horas = new ArrayList<>(filas.size());
        MetricasOperacion resumen = null;
        for (MetricasOperacion fila : filas) {
            if (fila.hora() == null) {
                resumen = fila;
            } else {
                horas.add(fila);
            }
        }
        if (resumen == null) {
            throw new IllegalStateException("La consulta del reporte no devolvió la fila de resumen");
        }
        return new ReporteOperacion(desde, hasta, resumen, horas);
    }
}
