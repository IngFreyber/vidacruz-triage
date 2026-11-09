package com.vidacruz.triage.application.service;

import com.vidacruz.triage.IntegracionBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Escalamiento por SLA (RF-03 / RN-03) contra PostgreSQL real")
class EscalamientoIntegrationTest extends IntegracionBase {

    /** E1: CRITICO, SLA 5 minutos. */
    private static final String E1 = """
            {"nombreCompleto":"Paciente Escalamiento","edad":58,"frecuenciaCardiaca":130,"presionSistolica":180,
             "presionDiastolica":110,"spo2":97,"temperatura":36.8,"dolor":8,"gcs":15,
             "quejaPrincipal":"Dolor torácico"}
            """;
    private static final Duration SLA_CRITICO = Duration.ofMinutes(5);

    @Autowired
    WebTestClient web;

    @Autowired
    DatabaseClient db;

    @Autowired
    EscalarPacientesService escalar;

    private record Registrado(String id, Instant fechaRegistro) {
        Instant vencimiento() {
            return fechaRegistro.plus(SLA_CRITICO);
        }
    }

    private Registrado registrar() {
        Map<?, ?> body = web.post().uri("/triage/pacientes").contentType(MediaType.APPLICATION_JSON).bodyValue(E1)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return new Registrado((String) body.get("id"), Instant.parse((String) body.get("fechaRegistro")));
    }

    private void cambiarEstado(String id, String estado) {
        web.patch().uri("/triage/pacientes/{id}/estado", id).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("estado", estado)).exchange().expectStatus().isOk();
    }

    private long contar(String tabla, String columnaPaciente, String id) {
        return db.sql("SELECT count(*) AS n FROM " + tabla + " WHERE " + columnaPaciente + " = :id "
                        + "AND tipo_evento = 'paciente_escalado'")
                .bind("id", UUID.fromString(id))
                .map(r -> r.get("n", Long.class)).one().block();
    }

    private long auditoriasEscalado(String id) {
        return contar("auditoria_clinica", "paciente_id", id);
    }

    private long eventosEscalado(String id) {
        return contar("outbox_eventos", "agregado_id", id);
    }

    @Test
    @DisplayName("Al superar el SLA se marca ESCALADO con auditoría y evento paciente_escalado")
    void escalaAlSuperarElSla() {
        Registrado p = registrar();

        escalar.escalarVencidos(p.vencimiento().plusSeconds(1)).block();

        web.get().uri("/triage/pacientes/{id}", p.id()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.estado").isEqualTo("REGISTRADO")
                .jsonPath("$.flags.escalado").isEqualTo(true)
                .jsonPath("$.flags.fechaEscalamiento").isNotEmpty();

        web.get().uri("/triage/pacientes/{id}/auditoria", p.id()).exchange()
                .expectBody()
                .jsonPath("$[1].tipoEvento").isEqualTo("paciente_escalado")
                .jsonPath("$[1].estadoAnterior").isEqualTo("REGISTRADO")
                .jsonPath("$[1].estadoNuevo").isEqualTo("REGISTRADO")
                .jsonPath("$[1].detalle").value(d -> assertThat((String) d).contains("false → true"));

        assertThat(eventosEscalado(p.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("No escala mientras el SLA no se haya superado")
    void noEscalaAntesDelVencimiento() {
        Registrado p = registrar();

        escalar.escalarVencidos(p.vencimiento()).block();

        assertThat(auditoriasEscalado(p.id())).isZero();
        web.get().uri("/triage/pacientes/{id}", p.id()).exchange()
                .expectBody().jsonPath("$.flags.escalado").isEqualTo(false);
    }

    @Test
    @DisplayName("RN-03: evaluaciones sucesivas no publican otro evento ni duplican auditoría")
    void escalaUnaUnicaVez() {
        Registrado p = registrar();

        escalar.escalarVencidos(p.vencimiento().plusSeconds(1)).block();
        escalar.escalarVencidos(p.vencimiento().plusSeconds(60)).block();
        escalar.escalarVencidos(p.vencimiento().plus(Duration.ofHours(2))).block();

        assertThat(auditoriasEscalado(p.id())).isEqualTo(1);
        assertThat(eventosEscalado(p.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("RN-03: dos monitores concurrentes (p. ej. dos réplicas) escalan una sola vez")
    void monitoresConcurrentesEscalanUnaVez() {
        Registrado p = registrar();
        Instant ahora = p.vencimiento().plusSeconds(1);

        Mono.zip(escalar.escalarVencidos(ahora), escalar.escalarVencidos(ahora), escalar.escalarVencidos(ahora))
                .block();

        assertThat(auditoriasEscalado(p.id())).isEqualTo(1);
        assertThat(eventosEscalado(p.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("RN-03: un paciente EN_CONSULTA nunca se escala")
    void nuncaEscalaEnConsulta() {
        Registrado p = registrar();
        cambiarEstado(p.id(), "EN_CONSULTA");

        escalar.escalarVencidos(p.vencimiento().plus(Duration.ofHours(3))).block();

        assertThat(auditoriasEscalado(p.id())).isZero();
    }

    @Test
    @DisplayName("RN-03: un paciente de ALTA nunca se escala")
    void nuncaEscalaAlta() {
        Registrado p = registrar();
        cambiarEstado(p.id(), "ALTA");

        escalar.escalarVencidos(p.vencimiento().plus(Duration.ofHours(3))).block();

        assertThat(auditoriasEscalado(p.id())).isZero();
    }

    @Test
    @DisplayName("Un paciente escalado puede entrar a consulta y conserva el flag")
    void escaladoEntraAConsultaConservandoElFlag() {
        Registrado p = registrar();
        escalar.escalarVencidos(p.vencimiento().plusSeconds(1)).block();

        cambiarEstado(p.id(), "EN_CONSULTA");

        web.get().uri("/triage/pacientes/{id}", p.id()).exchange()
                .expectBody()
                .jsonPath("$.estado").isEqualTo("EN_CONSULTA")
                .jsonPath("$.flags.escalado").isEqualTo(true)
                .jsonPath("$.flags.slaVencido").isEqualTo(false);
    }
}
