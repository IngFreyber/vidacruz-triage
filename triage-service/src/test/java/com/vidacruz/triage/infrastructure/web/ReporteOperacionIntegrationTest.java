package com.vidacruz.triage.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.IntegracionBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RF-05 con un conjunto de datos controlado (2020-03-01, aislado del resto de tests), valores esperados
 * calculados a mano:
 *
 * <pre>
 * Hora 10:00  P1 CRITICO      reg 10:05  consulta 10:09            → espera  4 min
 *             P2 URGENTE      reg 10:10  consulta 10:22            → espera 12 min
 *             P3 URGENTE      reg 10:20  consulta 10:40 (y 11:30*) → espera 20 min (* se ignora: no es la primera)
 *             P4 NO_URGENTE_2 reg 10:50  alta directa              → sin espera (no entró a consulta)
 *             total 4 · atendidos 3 · promedio (4+12+20)/3 = 12.00
 *             p95 = percentile_cont: posición 0.95·(3−1) = 1.9 → 12 + 0.9·(20−12) = 19.20
 * Hora 11:00  sin registros → total 0, promedio y p95 null
 * Hora 12:00  P5 NO_URGENTE   reg 12:00  consulta 13:00            → espera 60 min (la consulta cae fuera
 *             del rango, pero la espera se atribuye a la hora de registro)
 * 13:00       P6 CRITICO      reg 13:00  → fuera del rango: 'hasta' es exclusivo
 *
 * Resumen     total 5 · atendidos 4 · esperas [4, 12, 20, 60] → promedio 24.00
 *             p95: posición 0.95·(4−1) = 2.85 → 20 + 0.85·(60−20) = 54.00
 * </pre>
 */
@DisplayName("Reporte de operación (RF-05) con SQL avanzado")
class ReporteOperacionIntegrationTest extends IntegracionBase {

    private static final String DIA = "2020-03-01T";
    private static final String URL = "/triage/reportes/operacion?desde={d}&hasta={h}";
    private static boolean datosCargados;

    @Autowired
    WebTestClient web;

    @Autowired
    DatabaseClient db;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void cargarDatos() {
        if (datosCargados) {
            return;
        }
        UUID p1 = paciente("CRITICO", "10:05", "EN_CONSULTA");
        consulta(p1, "10:09");
        UUID p2 = paciente("URGENTE", "10:10", "EN_CONSULTA");
        consulta(p2, "10:22");
        UUID p3 = paciente("URGENTE", "10:20", "EN_CONSULTA");
        consulta(p3, "10:40");
        consulta(p3, "11:30");
        paciente("NO_URGENTE_2", "10:50", "ALTA");
        UUID p5 = paciente("NO_URGENTE", "12:00", "EN_CONSULTA");
        consulta(p5, "13:00");
        UUID p6 = paciente("CRITICO", "13:00", "EN_CONSULTA");
        consulta(p6, "13:01");
        datosCargados = true;
    }

    private JsonNode reporte(String desde, String hasta) throws Exception {
        byte[] body = web.get().uri(URL, desde, hasta).exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        return mapper.readTree(body);
    }

    @Test
    @DisplayName("Agrupa por hora con conteos por nivel, promedio y p95 de la espera")
    void metricasPorHora() throws Exception {
        JsonNode r = reporte(DIA + "10:00:00Z", DIA + "13:00:00Z");

        assertThat(r.get("zonaHoraria").asText()).isEqualTo("UTC");
        JsonNode horas = r.get("horas");
        assertThat(horas).hasSize(3);

        JsonNode h10 = horas.get(0);
        assertThat(h10.get("hora").asText()).isEqualTo("2020-03-01T10:00:00Z");
        assertThat(h10.get("totalRegistrados").asLong()).isEqualTo(4);
        assertThat(h10.get("porNivel").get("CRITICO").asLong()).isEqualTo(1);
        assertThat(h10.get("porNivel").get("URGENTE").asLong()).isEqualTo(2);
        assertThat(h10.get("porNivel").get("URGENTE_2").asLong()).isZero();
        assertThat(h10.get("porNivel").get("NO_URGENTE").asLong()).isZero();
        assertThat(h10.get("porNivel").get("NO_URGENTE_2").asLong()).isEqualTo(1);
        assertThat(h10.get("pacientesAtendidos").asLong()).isEqualTo(3);
        assertThat(h10.get("esperaPromedioMinutos").decimalValue()).isEqualByComparingTo("12.00");
        assertThat(h10.get("esperaP95Minutos").decimalValue()).isEqualByComparingTo("19.20");

        JsonNode h11 = horas.get(1);
        assertThat(h11.get("hora").asText()).isEqualTo("2020-03-01T11:00:00Z");
        assertThat(h11.get("totalRegistrados").asLong()).isZero();
        assertThat(h11.get("pacientesAtendidos").asLong()).isZero();
        assertThat(h11.has("esperaPromedioMinutos")).isTrue();
        assertThat(h11.get("esperaPromedioMinutos").isNull()).isTrue();
        assertThat(h11.get("esperaP95Minutos").isNull()).isTrue();

        JsonNode h12 = horas.get(2);
        assertThat(h12.get("totalRegistrados").asLong()).isEqualTo(1);
        assertThat(h12.get("porNivel").get("NO_URGENTE").asLong()).isEqualTo(1);
        assertThat(h12.get("esperaPromedioMinutos").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(h12.get("esperaP95Minutos").decimalValue()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("El resumen agrega el rango completo (ROLLUP), con p95 sobre todas las esperas")
    void resumenDelRango() throws Exception {
        JsonNode resumen = reporte(DIA + "10:00:00Z", DIA + "13:00:00Z").get("resumen");

        assertThat(resumen.has("hora")).isFalse();
        assertThat(resumen.get("totalRegistrados").asLong()).isEqualTo(5);
        assertThat(resumen.get("porNivel").get("CRITICO").asLong()).isEqualTo(1);
        assertThat(resumen.get("pacientesAtendidos").asLong()).isEqualTo(4);
        assertThat(resumen.get("esperaPromedioMinutos").decimalValue()).isEqualByComparingTo("24.00");
        assertThat(resumen.get("esperaP95Minutos").decimalValue()).isEqualByComparingTo("54.00");
    }

    @Test
    @DisplayName("'hasta' es exclusivo y una zona horaria distinta denota el mismo instante")
    void rangoSemiabiertoYZonas() throws Exception {
        JsonNode conOffset = reporte("2020-03-01T05:00:00-05:00", "2020-03-01T09:00:00-05:00");

        assertThat(conOffset.get("horas")).hasSize(4);
        assertThat(conOffset.get("resumen").get("totalRegistrados").asLong()).isEqualTo(6);
        assertThat(conOffset.get("horas").get(3).get("hora").asText()).isEqualTo("2020-03-01T13:00:00Z");
    }

    @Test
    @DisplayName("Acepta solo fecha (día completo en UTC)")
    void soloFecha() throws Exception {
        JsonNode dia = reporte("2020-03-01", "2020-03-02");

        assertThat(dia.get("horas")).hasSize(24);
        assertThat(dia.get("resumen").get("totalRegistrados").asLong()).isEqualTo(6);
    }

    @Nested
    @DisplayName("Validación de parámetros")
    class Validacion {

        @Test
        void parametrosAusentesSeReportanAmbos() {
            web.get().uri("/triage/reportes/operacion").exchange()
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.errores.length()").isEqualTo(2)
                    .jsonPath("$.errores[0].campo").isEqualTo("desde")
                    .jsonPath("$.errores[1].campo").isEqualTo("hasta");
        }

        @Test
        void formatoInvalido() {
            web.get().uri(URL, "ayer", DIA + "13:00:00Z").exchange()
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.errores[0].campo").isEqualTo("desde")
                    .jsonPath("$.errores[0].mensaje").value(m -> assertThat((String) m).contains("ISO 8601"));
        }

        @Test
        void desdePosteriorAHasta() {
            web.get().uri(URL, DIA + "13:00:00Z", DIA + "10:00:00Z").exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.errores[0].mensaje")
                    .value(m -> assertThat((String) m).contains("posterior"));
        }

        @Test
        void rangoDemasiadoAmplio() {
            web.get().uri(URL, "2020-01-01", "2020-03-01").exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.errores[0].mensaje")
                    .value(m -> assertThat((String) m).contains("31 días"));
        }
    }

    // --- carga directa de datos controlados (la auditoría solo admite INSERT) ---

    private UUID paciente(String nivel, String horaRegistro, String estado) {
        UUID id = UUID.randomUUID();
        Instant registro = Instant.parse(DIA + horaRegistro + ":00Z");
        db.sql("""
                        INSERT INTO pacientes (id, nombre_completo, edad, frecuencia_cardiaca, presion_sistolica,
                            presion_diastolica, spo2, temperatura, dolor, gcs, queja_principal, nivel, sla_segundos,
                            estado, fecha_registro, fecha_cambio_estado)
                        VALUES (:id, 'Paciente Reporte', 40, 72, 120, 80, 99, 36.5, 2, 15, 'Control', :nivel, 300,
                            :estado, :registro, :registro)
                        """)
                .bind("id", id).bind("nivel", nivel).bind("estado", estado).bind("registro", registro)
                .then().block();
        auditoria(id, "paciente_registrado", null, "REGISTRADO", nivel, registro);
        return id;
    }

    private void consulta(UUID id, String hora) {
        String nivel = db.sql("SELECT nivel FROM pacientes WHERE id = :id").bind("id", id)
                .map(r -> r.get("nivel", String.class)).one().block();
        auditoria(id, "paciente_en_consulta", "REGISTRADO", "EN_CONSULTA", nivel, Instant.parse(DIA + hora + ":00Z"));
    }

    private void auditoria(UUID id, String tipo, String anterior, String nuevo, String nivel, Instant cuando) {
        var spec = db.sql("""
                        INSERT INTO auditoria_clinica (evento_id, paciente_id, tipo_evento, estado_anterior,
                            estado_nuevo, nivel, detalle, ocurrido_en)
                        VALUES (:evento, :paciente, :tipo, :anterior, :nuevo, :nivel, 'dato de prueba', :cuando)
                        """)
                .bind("evento", UUID.randomUUID()).bind("paciente", id).bind("tipo", tipo)
                .bind("nuevo", nuevo).bind("nivel", nivel).bind("cuando", cuando);
        spec = anterior == null ? spec.bindNull("anterior", String.class) : spec.bind("anterior", anterior);
        spec.then().block();
    }
}
