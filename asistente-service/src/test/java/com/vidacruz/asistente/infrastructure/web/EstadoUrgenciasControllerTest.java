package com.vidacruz.asistente.infrastructure.web;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.vidacruz.asistente.application.service.EstadoUrgenciasService;
import com.vidacruz.asistente.domain.urgencias.EstadoUrgencias;
import com.vidacruz.asistente.domain.urgencias.NivelTriage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GET /asistente/estado-urgencias")
class EstadoUrgenciasControllerTest {

    private final EstadoUrgenciasService servicio = mock(EstadoUrgenciasService.class);
    private final WebTestClient web = WebTestClient
            .bindToController(new EstadoUrgenciasController(servicio))
            .controllerAdvice(new ManejadorErrores())
            // Mismo ObjectMapper que configura Spring Boot (fechas ISO 8601, no timestamps numéricos).
            .httpMessageCodecs(c -> c.defaultCodecs().jackson2JsonEncoder(
                    new Jackson2JsonEncoder(Jackson2ObjectMapperBuilder.json()
                            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build())))
            .build();

    @Test
    void devuelveLosCincoNivelesYElTotal() {
        when(servicio.consultar()).thenReturn(Mono.just(new EstadoUrgencias(
                Map.of(NivelTriage.CRITICO, 2L, NivelTriage.NO_URGENTE, 3L), Instant.parse("2026-11-09T10:00:00Z"))));

        web.get().uri("/asistente/estado-urgencias").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalActivos").isEqualTo(5)
                .jsonPath("$.porNivel.CRITICO").isEqualTo(2)
                .jsonPath("$.porNivel.URGENTE").isEqualTo(0)
                .jsonPath("$.porNivel.NO_URGENTE_2").isEqualTo(0)
                .jsonPath("$.actualizadoEn").isEqualTo("2026-11-09T10:00:00Z");
    }

    @Test
    @DisplayName("Con Redis caído responde 503 con un mensaje claro, no 500")
    void redisCaidoResponde503() {
        when(servicio.consultar()).thenReturn(Mono.error(
                new RedisConnectionFailureException("Unable to connect to Redis")));

        web.get().uri("/asistente/estado-urgencias").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Estado de urgencias no disponible")
                .jsonPath("$.detail").value(d -> org.assertj.core.api.Assertions.assertThat((String) d)
                        .contains("Reintente"));
    }
}
