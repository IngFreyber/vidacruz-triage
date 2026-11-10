package com.vidacruz.alertas.infrastructure.observabilidad;

import com.vidacruz.alertas.application.port.out.MetricasAlertas;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Contadores Micrometer: {@code alertas_emitidas_total{nivel}} y {@code alertas_notificaciones_total{canal,resultado}}.
 * Una tasa creciente de {@code resultado="error"} en Teams es la señal para revisar el webhook.
 */
@Component
public class MicrometerMetricasAlertas implements MetricasAlertas {

    private final MeterRegistry registry;

    public MicrometerMetricasAlertas(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void alertaEmitida(String nivel) {
        registry.counter("alertas.emitidas", "nivel", nivel).increment();
    }

    @Override
    public void notificacion(String canal, boolean exitosa) {
        registry.counter("alertas.notificaciones", "canal", canal, "resultado", exitosa ? "ok" : "error").increment();
    }
}
