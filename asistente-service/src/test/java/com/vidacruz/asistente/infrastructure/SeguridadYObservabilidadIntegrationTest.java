package com.vidacruz.asistente.infrastructure;

import com.vidacruz.asistente.IntegracionBase;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@DisplayName("Asistente: seguridad por API key y métricas de observabilidad")
class SeguridadYObservabilidadIntegrationTest extends IntegracionBase {

    @LocalServerPort
    int puerto;

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void reiniciar() {
        reset(chatModel);
        circuitBreakers.circuitBreaker("llm").reset();
    }

    private WebTestClient anonimo() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
    }

    @Test
    void consultaSinApiKeyResponde401() {
        anonimo().post().uri("/asistente/consulta").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("pregunta", "¿Cuáles son los criterios qSOFA?")).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void estadoUrgenciasSinApiKeyResponde401() {
        anonimo().get().uri("/asistente/estado-urgencias").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void probesPublicas() {
        anonimo().get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        anonimo().get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("asistente.llm.latencia y asistente.consultas{estado} se exponen en /actuator/prometheus")
    void metricasDelAsistente() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("""
                        {"respuesta":"Lactato y hemocultivos antes del antibiótico.",
                         "fuentes":[{"protocolo":"PROTO-002","seccion":"Acciones iniciales"}],"sinInformacion":false}
                        """)))));

        anonimo().post().uri("/asistente/consulta").header(HEADER_API_KEY, API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("pregunta", "¿Cuándo se administra el primer antibiótico en sepsis?"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("X-Trace-Id", "[0-9a-f]{32}");

        anonimo().get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(cuerpo -> assertThat(cuerpo)
                        .contains("asistente_llm_latencia_seconds_count{")
                        .contains("resultado=\"ok\"")
                        .contains("asistente_llm_latencia_seconds_bucket")
                        .contains("asistente_consultas_total{")
                        .contains("estado=\"RESPONDIDA\"")
                        .contains("resilience4j_circuitbreaker_state"));
    }
}
