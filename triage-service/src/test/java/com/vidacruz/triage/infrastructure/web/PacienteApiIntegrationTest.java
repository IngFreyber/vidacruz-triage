package com.vidacruz.triage.infrastructure.web;

import com.vidacruz.triage.IntegracionBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("API de pacientes (RF-01, RF-02, RN-04) contra PostgreSQL real")
class PacienteApiIntegrationTest extends IntegracionBase {

    private static final String E1 = """
            {"nombreCompleto":"Paciente E1","edad":58,"frecuenciaCardiaca":130,"presionSistolica":180,
             "presionDiastolica":110,"spo2":97,"temperatura":36.8,"dolor":8,"gcs":15,
             "quejaPrincipal":"Dolor torácico"}
            """;

    @Autowired
    WebTestClient web;

    @Autowired
    DatabaseClient db;

    @LocalServerPort
    int puerto;

    private String registrar(String json) {
        Map<?, ?> body = web.post().uri("/triage/pacientes").contentType(MediaType.APPLICATION_JSON).bodyValue(json)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("id");
    }

    private WebTestClient.ResponseSpec cambiarEstado(String id, String estado) {
        return web.patch().uri("/triage/pacientes/{id}/estado", id).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("estado", estado)).exchange();
    }

    @Nested
    @DisplayName("POST /triage/pacientes")
    class Registro {

        @ParameterizedTest(name = "{0} → {9} (SLA {10} min)")
        @CsvSource(delimiter = '|', textBlock = """
                E1 | 130 | 180 | 110 | 97 | 36.8 | 8 | 15 | Dolor torácico           | CRITICO      | 5
                E2 |  95 | 150 |  95 | 91 | 37.0 | 4 | 15 | Dificultad para respirar | URGENTE      | 10
                E3 |  88 | 130 |  85 | 98 | 38.2 | 6 | 15 | Fiebre                   | URGENTE_2    | 30
                E4 |  72 | 120 |  80 | 99 | 36.5 | 2 | 15 | Herida por cortar        | NO_URGENTE   | 60
                E5 |  70 | 115 |  75 | 99 | 36.4 | 0 | 15 | Renovación de recetario  | NO_URGENTE_2 | 120
                E6 |  35 |  80 |  50 | 85 | 35.9 | 0 |  6 | Inconsciente             | CRITICO      | 5
                """)
        void registraYClasificaPacientesDeReferencia(String caso, int fc, int sist, int dias, int spo2, double temp,
                                                     int dolor, int gcs, String queja, String nivel, int sla) {
            Map<String, Object> body = Map.of("nombreCompleto", "Paciente " + caso, "edad", 40,
                    "frecuenciaCardiaca", fc, "presionSistolica", sist, "presionDiastolica", dias, "spo2", spo2,
                    "temperatura", temp, "dolor", dolor, "gcs", gcs, "quejaPrincipal", queja);

            web.post().uri("/triage/pacientes").bodyValue(body).exchange()
                    .expectStatus().isCreated()
                    .expectHeader().valueMatches("Location", ".*/triage/pacientes/[0-9a-f-]{36}$")
                    .expectBody()
                    .jsonPath("$.id").isNotEmpty()
                    .jsonPath("$.nivel").isEqualTo(nivel)
                    .jsonPath("$.slaMinutos").isEqualTo(sla)
                    .jsonPath("$.estado").isEqualTo("REGISTRADO")
                    .jsonPath("$.fechaRegistro").value(f -> assertThat((String) f)
                            .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"));
        }

        @Test
        void valorFueraDeRangoResponde400IndicandoElCampo() {
            web.post().uri("/triage/pacientes").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(E1.replace("\"spo2\":97", "\"spo2\":65"))
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(400)
                    .jsonPath("$.errores[0].campo").isEqualTo("spo2")
                    .jsonPath("$.errores[0].mensaje").value(m -> assertThat((String) m).contains("entre 70 y 100"));
        }

        @Test
        void diastolicaMayorOIgualQueSistolicaResponde400() {
            web.post().uri("/triage/pacientes").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(E1.replace("\"presionDiastolica\":110", "\"presionDiastolica\":150")
                            .replace("\"presionSistolica\":180", "\"presionSistolica\":140"))
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.errores[0].campo").isEqualTo("presionDiastolica");
        }

        @Test
        void tipoDeDatoIncorrectoResponde400ConElCampo() {
            web.post().uri("/triage/pacientes").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(E1.replace("\"edad\":58", "\"edad\":\"cincuenta\""))
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.errores[0].campo").isEqualTo("edad");
        }

        @Test
        void decimalEnCampoEnteroResponde400() {
            web.post().uri("/triage/pacientes").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(E1.replace("\"edad\":58", "\"edad\":58.7"))
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.errores[0].campo").isEqualTo("edad");
        }

        @Test
        void jsonMalFormadoResponde400() {
            web.post().uri("/triage/pacientes").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"edad\": ")
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.title").isEqualTo("Petición inválida");
        }

        @Test
        void camposObligatoriosAusentesSeReportanTodos() {
            web.post().uri("/triage/pacientes").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.errores.length()").isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("GET /triage/pacientes/{id}")
    class Consulta {

        @Test
        void devuelveDatosCompletosEstadoFlagsYTiempoEnEstado() {
            String id = registrar(E1);

            web.get().uri("/triage/pacientes/{id}", id).exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.id").isEqualTo(id)
                    .jsonPath("$.nombreCompleto").isEqualTo("Paciente E1")
                    .jsonPath("$.signosVitales.frecuenciaCardiaca").isEqualTo(130)
                    .jsonPath("$.signosVitales.temperatura").isEqualTo(36.8)
                    .jsonPath("$.nivel").isEqualTo("CRITICO")
                    .jsonPath("$.criteriosClasificacion[0]").isEqualTo("FC > 120 (130)")
                    .jsonPath("$.estado").isEqualTo("REGISTRADO")
                    .jsonPath("$.flags.escalado").isEqualTo(false)
                    .jsonPath("$.flags.slaVencido").isEqualTo(false)
                    .jsonPath("$.tiempoEnEstadoActual.segundos").isNumber()
                    .jsonPath("$.tiempoEnEstadoActual.iso8601").value(v -> assertThat((String) v).startsWith("PT"));
        }

        @Test
        void pacienteInexistenteResponde404() {
            web.get().uri("/triage/pacientes/{id}", UUID.randomUUID()).exchange()
                    .expectStatus().isNotFound()
                    .expectBody().jsonPath("$.title").isEqualTo("Paciente no encontrado");
        }

        @Test
        void idQueNoEsUuidResponde400() {
            web.get().uri("/triage/pacientes/no-es-un-uuid").exchange().expectStatus().isBadRequest();
        }
    }

    @Nested
    @DisplayName("PATCH /triage/pacientes/{id}/estado")
    class FlujoEstados {

        @Test
        void registradoEnConsultaAlta() {
            String id = registrar(E1);

            cambiarEstado(id, "EN_CONSULTA").expectStatus().isOk()
                    .expectBody().jsonPath("$.estado").isEqualTo("EN_CONSULTA");
            cambiarEstado(id, "ALTA").expectStatus().isOk()
                    .expectBody().jsonPath("$.estado").isEqualTo("ALTA");
        }

        @Test
        void altaDirecta() {
            String id = registrar(E1);

            cambiarEstado(id, "ALTA").expectStatus().isOk().expectBody().jsonPath("$.estado").isEqualTo("ALTA");
        }

        @Test
        void transicionInvalidaResponde409ConMensajeDescriptivo() {
            String id = registrar(E1);
            cambiarEstado(id, "ALTA").expectStatus().isOk();

            cambiarEstado(id, "EN_CONSULTA")
                    .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                    .expectBody()
                    .jsonPath("$.detail").value(d -> assertThat((String) d).contains("ALTA → EN_CONSULTA"))
                    .jsonPath("$.estadoActual").isEqualTo("ALTA")
                    .jsonPath("$.estadoSolicitado").isEqualTo("EN_CONSULTA");
        }

        @Test
        void volverARegistradoResponde409() {
            String id = registrar(E1);
            cambiarEstado(id, "EN_CONSULTA").expectStatus().isOk();

            cambiarEstado(id, "REGISTRADO").expectStatus().isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void estadoDesconocidoResponde400() {
            String id = registrar(E1);

            cambiarEstado(id, "DORMIDO").expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.errores[0].campo").isEqualTo("estado");
        }

        @Test
        void pacienteInexistenteResponde404() {
            cambiarEstado(UUID.randomUUID().toString(), "ALTA").expectStatus().isNotFound();
        }

        @Test
        @DisplayName("Peticiones concurrentes: solo una transición gana, las demás reciben 409")
        void concurrenciaOptimista() {
            String id = registrar(E1);
            WebClient cliente = WebClient.builder().baseUrl("http://localhost:" + puerto)
                    .defaultHeader(HEADER_API_KEY, API_KEY).build();

            List<Integer> estados = Flux.range(0, 6)
                    .flatMap(i -> cliente.patch().uri("/triage/pacientes/{id}/estado", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of("estado", "EN_CONSULTA"))
                            .exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value())))
                    .collectList()
                    .block();

            assertThat(estados).containsOnly(200, 409);
            assertThat(estados).filteredOn(s -> s == 200).hasSize(1);
            StepVerifier.create(db.sql("SELECT count(*) AS n FROM auditoria_clinica WHERE paciente_id = :id "
                                    + "AND tipo_evento = 'paciente_en_consulta'")
                            .bind("id", UUID.fromString(id)).map(r -> r.get("n", Long.class)).one())
                    .expectNext(1L)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Auditoría clínica y outbox (RN-04)")
    class Auditoria {

        @Test
        void cadaCambioQuedaAuditadoYEnElOutbox() {
            String id = registrar(E1);
            cambiarEstado(id, "EN_CONSULTA").expectStatus().isOk();
            cambiarEstado(id, "ALTA").expectStatus().isOk();

            web.get().uri("/triage/pacientes/{id}/auditoria", id).exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.length()").isEqualTo(3)
                    .jsonPath("$[0].tipoEvento").isEqualTo("paciente_registrado")
                    .jsonPath("$[0].estadoAnterior").doesNotExist()
                    .jsonPath("$[0].estadoNuevo").isEqualTo("REGISTRADO")
                    .jsonPath("$[1].tipoEvento").isEqualTo("paciente_en_consulta")
                    .jsonPath("$[1].estadoAnterior").isEqualTo("REGISTRADO")
                    .jsonPath("$[1].estadoNuevo").isEqualTo("EN_CONSULTA")
                    .jsonPath("$[2].tipoEvento").isEqualTo("paciente_alta")
                    .jsonPath("$[2].timestamp").isNotEmpty();

            StepVerifier.create(db.sql("SELECT tipo_evento, payload->>'nivel' AS nivel FROM outbox_eventos "
                                    + "WHERE agregado_id = :id ORDER BY id")
                            .bind("id", UUID.fromString(id))
                            .map(r -> r.get("tipo_evento", String.class) + ":" + r.get("nivel", String.class))
                            .all())
                    .expectNext("paciente_registrado:CRITICO", "paciente_en_consulta:CRITICO", "paciente_alta:CRITICO")
                    .verifyComplete();
        }

        @Test
        void unaTransicionInvalidaNoDejaAuditoria() {
            String id = registrar(E1);
            cambiarEstado(id, "ALTA").expectStatus().isOk();
            cambiarEstado(id, "EN_CONSULTA").expectStatus().isEqualTo(HttpStatus.CONFLICT);

            web.get().uri("/triage/pacientes/{id}/auditoria", id).exchange()
                    .expectBody().jsonPath("$.length()").isEqualTo(2);
        }

        @Test
        void noExisteEndpointDeBorrado() {
            String id = registrar(E1);

            web.delete().uri("/triage/pacientes/{id}/auditoria", id).exchange()
                    .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            web.delete().uri("/triage/pacientes/{id}", id).exchange()
                    .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }

        @Test
        @DisplayName("La base de datos rechaza UPDATE y DELETE sobre la auditoría")
        void laAuditoriaEsInmutableEnLaBaseDeDatos() {
            String id = registrar(E1);

            StepVerifier.create(db.sql("DELETE FROM auditoria_clinica WHERE paciente_id = :id")
                            .bind("id", UUID.fromString(id)).fetch().rowsUpdated())
                    .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("inmutable"))
                    .verify();
            StepVerifier.create(db.sql("UPDATE auditoria_clinica SET detalle = 'x' WHERE paciente_id = :id")
                            .bind("id", UUID.fromString(id)).fetch().rowsUpdated())
                    .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("inmutable"))
                    .verify();
        }
    }
}
