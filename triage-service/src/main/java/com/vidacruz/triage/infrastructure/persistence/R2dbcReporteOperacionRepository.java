package com.vidacruz.triage.infrastructure.persistence;

import com.vidacruz.triage.application.port.out.ReporteOperacionRepository;
import com.vidacruz.triage.application.reporte.MetricasOperacion;
import com.vidacruz.triage.domain.model.NivelTriage;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * RF-05 resuelto íntegramente en PostgreSQL con una sola consulta:
 *
 * <ol>
 *   <li>{@code horas}: {@code generate_series} genera todas las horas del rango (también las vacías).</li>
 *   <li>{@code registrados}: pacientes con registro en [desde, hasta), con su hora truncada en UTC.</li>
 *   <li>{@code primera_consulta}: window function {@code ROW_NUMBER() OVER (PARTITION BY paciente ORDER BY
 *       ocurrido_en)} sobre la auditoría para tomar la <i>primera</i> entrada a consulta de cada paciente.</li>
 *   <li>{@code esperas}: minutos registro → primera consulta ({@code NULL} si aún no entró).</li>
 *   <li>Agregación con {@code COUNT(*) FILTER (WHERE nivel = …)}, {@code AVG} y
 *       {@code percentile_cont(0.95) WITHIN GROUP (ORDER BY …)}; {@code GROUP BY ROLLUP(hora)} agrega además
 *       la fila de resumen del rango completo ({@code GROUPING(hora) = 1}).</li>
 * </ol>
 * El p95 es el percentil continuo (interpolación lineal), igual que {@code PERCENTILE_CONT} de SQL estándar.
 */
@Repository
public class R2dbcReporteOperacionRepository implements ReporteOperacionRepository {

    static final String SQL = """
            WITH horas AS (
                SELECT generate_series(
                           date_trunc('hour', CAST(:desde AS timestamptz), 'UTC'),
                           date_trunc('hour', CAST(:hasta AS timestamptz) - INTERVAL '1 microsecond', 'UTC'),
                           INTERVAL '1 hour') AS hora
            ),
            registrados AS (
                SELECT p.id, p.nivel, p.fecha_registro,
                       date_trunc('hour', p.fecha_registro, 'UTC') AS hora
                  FROM pacientes p
                 WHERE p.fecha_registro >= :desde
                   AND p.fecha_registro <  :hasta
            ),
            primera_consulta AS (
                SELECT paciente_id, ocurrido_en
                  FROM (SELECT a.paciente_id,
                               a.ocurrido_en,
                               ROW_NUMBER() OVER (PARTITION BY a.paciente_id
                                                  ORDER BY a.ocurrido_en, a.id) AS orden
                          FROM auditoria_clinica a
                         WHERE a.tipo_evento = 'paciente_en_consulta'
                           AND a.paciente_id IN (SELECT id FROM registrados)) consultas
                 WHERE orden = 1
            ),
            esperas AS (
                SELECT r.hora,
                       r.nivel,
                       EXTRACT(EPOCH FROM (pc.ocurrido_en - r.fecha_registro)) / 60.0 AS espera_min
                  FROM registrados r
                  LEFT JOIN primera_consulta pc ON pc.paciente_id = r.id
            )
            SELECT GROUPING(h.hora) = 1                                    AS es_resumen,
                   h.hora                                                  AS hora,
                   COUNT(e.nivel)                                          AS total,
                   COUNT(*) FILTER (WHERE e.nivel = 'CRITICO')             AS critico,
                   COUNT(*) FILTER (WHERE e.nivel = 'URGENTE')             AS urgente,
                   COUNT(*) FILTER (WHERE e.nivel = 'URGENTE_2')           AS urgente_2,
                   COUNT(*) FILTER (WHERE e.nivel = 'NO_URGENTE')          AS no_urgente,
                   COUNT(*) FILTER (WHERE e.nivel = 'NO_URGENTE_2')        AS no_urgente_2,
                   COUNT(e.espera_min)                                     AS atendidos,
                   ROUND(AVG(e.espera_min), 2)                             AS espera_promedio,
                   ROUND(CAST(percentile_cont(0.95) WITHIN GROUP
                              (ORDER BY CAST(e.espera_min AS double precision)) AS numeric), 2) AS espera_p95
              FROM horas h
              LEFT JOIN esperas e ON e.hora = h.hora
             GROUP BY ROLLUP (h.hora)
             ORDER BY es_resumen, h.hora
            """;

    private final DatabaseClient db;

    public R2dbcReporteOperacionRepository(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Flux<MetricasOperacion> metricasPorHora(Instant desde, Instant hasta) {
        return db.sql(SQL)
                .bind("desde", desde)
                .bind("hasta", hasta)
                .map(R2dbcReporteOperacionRepository::mapear)
                .all();
    }

    private static MetricasOperacion mapear(Readable row) {
        Map<NivelTriage, Long> porNivel = new EnumMap<>(NivelTriage.class);
        for (NivelTriage nivel : NivelTriage.values()) {
            porNivel.put(nivel, row.get(nivel.name().toLowerCase(Locale.ROOT), Long.class));
        }
        boolean esResumen = Boolean.TRUE.equals(row.get("es_resumen", Boolean.class));
        return new MetricasOperacion(
                esResumen ? null : row.get("hora", Instant.class),
                row.get("total", Long.class),
                porNivel,
                row.get("atendidos", Long.class),
                row.get("espera_promedio", BigDecimal.class),
                row.get("espera_p95", BigDecimal.class));
    }
}
