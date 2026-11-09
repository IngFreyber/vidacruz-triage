package com.vidacruz.triage.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vidacruz.triage.application.reporte.MetricasOperacion;
import com.vidacruz.triage.application.reporte.ReporteOperacion;
import com.vidacruz.triage.domain.model.NivelTriage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Respuesta de {@code GET /triage/reportes/operacion}.
 *
 * @param desde       inicio del rango (inclusivo)
 * @param hasta       fin del rango (exclusivo)
 * @param zonaHoraria zona en la que se agrupan las horas
 * @param resumen     métricas del rango completo
 * @param horas       métricas por hora, incluidas las horas sin registros
 */
public record ReporteOperacionResponse(
        Instant desde,
        Instant hasta,
        String zonaHoraria,
        Metricas resumen,
        List<Metricas> horas) {

    /**
     * Las esperas se serializan siempre (también como {@code null}) para que el consumidor distinga
     * "sin pacientes atendidos" de un campo ausente.
     */
    public record Metricas(
            Instant hora,
            long totalRegistrados,
            Map<NivelTriage, Long> porNivel,
            long pacientesAtendidos,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal esperaPromedioMinutos,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal esperaP95Minutos) {

        static Metricas desde(MetricasOperacion m) {
            return new Metricas(m.hora(), m.totalRegistrados(), m.porNivel(), m.pacientesAtendidos(),
                    m.esperaPromedioMinutos(), m.esperaP95Minutos());
        }
    }

    public static ReporteOperacionResponse desde(ReporteOperacion r) {
        return new ReporteOperacionResponse(r.desde(), r.hasta(), "UTC", Metricas.desde(r.resumen()),
                r.horas().stream().map(Metricas::desde).toList());
    }
}
