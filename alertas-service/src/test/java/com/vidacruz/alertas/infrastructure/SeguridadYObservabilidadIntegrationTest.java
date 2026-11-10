package com.vidacruz.alertas.infrastructure;

import com.vidacruz.alertas.IntegracionBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Alertas: seguridad por API key (incluido SSE) y métricas")
class SeguridadYObservabilidadIntegrationTest extends IntegracionBase {

    @LocalServerPort
    int puerto;

    private WebTestClient anonimo() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
    }

    @Test
    void historialSinApiKeyResponde401() {
        anonimo().get().uri("/alertas").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("SSE sin credencial responde 401")
    void streamSinApiKeyResponde401() {
        anonimo().get().uri("/alertas/stream").accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("SSE acepta la API key como parámetro (EventSource no admite cabeceras)")
    void streamConApiKeyEnQuery() {
        anonimo().get().uri("/alertas/stream?api_key=" + API_KEY).accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseBody().take(Duration.ofMillis(600)).blockLast();
    }

    @Test
    @DisplayName("La API key por parámetro NO se acepta fuera del stream (no queda en logs de acceso de otras rutas)")
    void apiKeyEnQueryRechazadaEnOtrasRutas() {
        anonimo().get().uri("/alertas?api_key=" + API_KEY).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void metricasExpuestas() {
        anonimo().get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(cuerpo -> assertThat(cuerpo)
                        .contains("application=\"alertas-service\"")
                        .contains("resilience4j_circuitbreaker_state"));
    }
}
