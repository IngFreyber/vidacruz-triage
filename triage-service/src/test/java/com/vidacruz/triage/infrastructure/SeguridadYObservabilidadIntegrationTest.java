package com.vidacruz.triage.infrastructure;

import com.vidacruz.triage.IntegracionBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Seguridad (API key) y observabilidad (probes, métricas, trazas, BlockHound)")
class SeguridadYObservabilidadIntegrationTest extends IntegracionBase {

    private static final String E1 = """
            {"nombreCompleto":"Paciente Seguridad","edad":58,"frecuenciaCardiaca":130,"presionSistolica":180,
             "presionDiastolica":110,"spo2":97,"temperatura":36.8,"dolor":8,"gcs":15,"quejaPrincipal":"Dolor torácico"}
            """;

    @LocalServerPort
    int puerto;

    @Autowired
    MeterRegistry metricas;

    /** Cliente SIN la API key por defecto. */
    private WebTestClient anonimo() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
    }

    @Nested
    @DisplayName("Autenticación por API key")
    class Autenticacion {

        @Test
        void sinApiKeyResponde401EnFormatoProblemDetail() {
            anonimo().get().uri("/triage/reportes/operacion?desde=2026-01-01&hasta=2026-01-02").exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectHeader().valueMatches("WWW-Authenticate", "ApiKey.*")
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(401)
                    .jsonPath("$.title").isEqualTo("No autenticado")
                    .jsonPath("$.detail").value(d -> assertThat((String) d).contains("X-API-Key"));
        }

        @Test
        void apiKeyIncorrectaResponde401() {
            anonimo().post().uri("/triage/pacientes").header(HEADER_API_KEY, "clave-equivocada-0000000000")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(E1).exchange()
                    .expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.detail").isEqualTo("API key inválida");
        }

        @Test
        void apiKeyValidaPermiteOperar() {
            anonimo().post().uri("/triage/pacientes").header(HEADER_API_KEY, API_KEY)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(E1).exchange()
                    .expectStatus().isCreated();
        }

        @Test
        @DisplayName("Las probes de Kubernetes son públicas")
        void probesPublicas() {
            anonimo().get().uri("/actuator/health/liveness").exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.status").isEqualTo("UP");
            anonimo().get().uri("/actuator/health/readiness").exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.status").isEqualTo("UP");
        }

        @Test
        @DisplayName("Los endpoints de actuator no listados exigen credencial")
        void actuatorSensibleProtegido() {
            anonimo().get().uri("/actuator/metrics").exchange().expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("Observabilidad")
    class Observabilidad {

        @Test
        @DisplayName("Métrica triage.clasificacion{nivel} y exposición en /actuator/prometheus")
        void metricaDeClasificacion() {
            double antes = metricas.get("triage.clasificacion").tag("nivel", "CRITICO").counter().count();

            anonimo().post().uri("/triage/pacientes").header(HEADER_API_KEY, API_KEY)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(E1).exchange().expectStatus().isCreated();

            assertThat(metricas.get("triage.clasificacion").tag("nivel", "CRITICO").counter().count())
                    .isEqualTo(antes + 1);
            anonimo().get().uri("/actuator/prometheus").exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class).value(cuerpo -> assertThat(cuerpo)
                            .contains("triage_clasificacion_total{application=\"triage-service\",nivel=\"CRITICO\"")
                            .contains("triage_escalamientos_total")
                            .contains("http_server_requests_seconds_bucket"));
        }

        @Test
        @DisplayName("Cada respuesta incluye X-Trace-Id para correlacionar con logs y trazas")
        void cabeceraTraceId() {
            anonimo().get().uri("/actuator/health/liveness").exchange()
                    .expectHeader().valueMatches("X-Trace-Id", "[0-9a-f]{32}");
        }

        @Test
        @DisplayName("BlockHound está activo: un bloqueo en un hilo no bloqueante falla")
        void blockHoundActivo() {
            StepVerifier.create(Mono.fromCallable(() -> {
                                Thread.sleep(10);
                                return "bloqueado";
                            })
                            .subscribeOn(Schedulers.parallel()))
                    .expectError(BlockingOperationError.class)
                    .verify(Duration.ofSeconds(5));
        }
    }
}
