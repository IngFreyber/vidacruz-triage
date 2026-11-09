package com.vidacruz.asistente.infrastructure.web;

import com.vidacruz.asistente.IntegracionBase;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RF-04 parte A y RN-05 de punta a punta: recuperación REAL (embeddings ONNX + umbral calibrado) y LLM simulado.
 * Cubre las tres consultas de ejemplo del enunciado, citas inventadas, salida no estructurada y modo degradado.
 */
@DisplayName("Asistente clínico con RAG: citas, anti-alucinación y modo degradado")
class ConsultaRagIntegrationTest extends IntegracionBase {

    private static final String PREGUNTA_1 = "¿Cuáles son las medidas iniciales ante una crisis hipertensiva?";
    private static final String PREGUNTA_2 = "¿Cuándo se administra el primer antibiótico en sepsis?";
    private static final String PREGUNTA_3 = "¿Cuál es el tratamiento de primera línea para la diabetes tipo 2?";

    @Autowired
    WebTestClient web;

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void reiniciar() {
        reset(chatModel);
        circuitBreakers.circuitBreaker("llm").reset();
    }

    private void llmResponde(String json) {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));
    }

    private WebTestClient.ResponseSpec consultar(String pregunta) {
        return web.post().uri("/asistente/consulta").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("pregunta", pregunta)).exchange();
    }

    private String mensajeUsuarioEnviado() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        return captor.getValue().getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .map(Message::getText)
                .findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("Consultas de ejemplo del enunciado")
    class Ejemplos {

        @Test
        @DisplayName("1) Crisis hipertensiva → contenido de PROTO-001 citado en fuentes")
        void crisisHipertensiva() {
            llmResponde("""
                    {"respuesta":"Reconfirmar la presión tras 5 minutos de reposo, no reducirla más de un 25% en la \
                    primera hora y vigilar signos de encefalopatía.",
                     "fuentes":[{"protocolo":"PROTO-001","seccion":"Medidas iniciales"}],"sinInformacion":false}
                    """);

            consultar(PREGUNTA_1).expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.estado").isEqualTo("RESPONDIDA")
                    .jsonPath("$.respuesta").value(r -> assertThat((String) r).contains("25%"))
                    .jsonPath("$.fuentes.length()").isEqualTo(1)
                    .jsonPath("$.fuentes[0].protocolo").isEqualTo("PROTO-001")
                    .jsonPath("$.fuentes[0].seccion").isEqualTo("Medidas iniciales");

            String enviado = mensajeUsuarioEnviado();
            assertThat(enviado).contains("[PROTO-001 | Medidas iniciales]")
                    .contains("No reducir la presión más de un 25% en la primera hora")
                    .contains("<pregunta>" + PREGUNTA_1 + "</pregunta>")
                    .as("las palabras clave del índice nunca se entregan al LLM")
                    .doesNotContain("hipertensión arterial severa")
                    .as("no se entregan protocolos irrelevantes")
                    .doesNotContain("PROTO-003");
        }

        @Test
        @DisplayName("2) Primer antibiótico en sepsis → contenido de PROTO-002 citado")
        void antibioticoEnSepsis() {
            llmResponde("""
                    {"respuesta":"El antibiótico de amplio espectro se administra en la primera hora, tras tomar \
                    lactato y hemocultivos.",
                     "fuentes":[{"protocolo":"PROTO-002","seccion":"Acciones iniciales"}],"sinInformacion":false}
                    """);

            consultar(PREGUNTA_2).expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.estado").isEqualTo("RESPONDIDA")
                    .jsonPath("$.fuentes[0].protocolo").isEqualTo("PROTO-002")
                    .jsonPath("$.fuentes[0].seccion").isEqualTo("Acciones iniciales");

            assertThat(mensajeUsuarioEnviado()).contains("[PROTO-002 | Acciones iniciales]")
                    .contains("Antibiótico de amplio espectro en la primera hora");
        }

        @Test
        @DisplayName("3) Diabetes tipo 2 → 'no existe protocolo disponible' SIN invocar al LLM")
        void diabetesSinProtocolo() {
            consultar(PREGUNTA_3).expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.estado").isEqualTo("SIN_PROTOCOLO")
                    .jsonPath("$.respuesta").isEqualTo(
                            "No existe un protocolo disponible en el corpus oficial para esta pregunta.")
                    .jsonPath("$.fuentes.length()").isEqualTo(0);

            verify(chatModel, never()).call(any(Prompt.class));
        }
    }

    @Nested
    @DisplayName("Anti-alucinación (RN-05)")
    class AntiAlucinacion {

        @Test
        @DisplayName("Una cita a un protocolo inexistente descarta la respuesta y no se entrega contenido clínico")
        void citaInventada() {
            llmResponde("""
                    {"respuesta":"Administrar labetalol 20 mg IV en bolo.",
                     "fuentes":[{"protocolo":"PROTO-001","seccion":"Medidas iniciales"},
                                {"protocolo":"PROTO-047","seccion":"Fármacos IV"}],"sinInformacion":false}
                    """);

            consultar(PREGUNTA_1).expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.estado").isEqualTo("RESPUESTA_DESCARTADA")
                    .jsonPath("$.fuentes.length()").isEqualTo(0)
                    .jsonPath("$.respuesta").value(r -> assertThat((String) r)
                            .doesNotContain("labetalol").contains("No existe un protocolo disponible"));
        }

        @Test
        @DisplayName("Citar una sección real del corpus que no se recuperó también se descarta")
        void citaNoRecuperada() {
            llmResponde("""
                    {"respuesta":"Solución oral 50 ml/kg.",
                     "fuentes":[{"protocolo":"PROTO-003","seccion":"Rehidratación"}],"sinInformacion":false}
                    """);

            consultar(PREGUNTA_1).expectStatus().isOk()
                    .expectBody().jsonPath("$.estado").isEqualTo("RESPUESTA_DESCARTADA");
        }

        @Test
        @DisplayName("Si el modelo declara que los fragmentos no responden, solo se entrega el aviso")
        void modeloSinInformacion() {
            llmResponde("""
                    {"respuesta":"","fuentes":[],"sinInformacion":true}
                    """);

            consultar(PREGUNTA_2).expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.estado").isEqualTo("SIN_PROTOCOLO")
                    .jsonPath("$.fuentes.length()").isEqualTo(0);
        }

        @Test
        @DisplayName("Texto libre sin el JSON exigido (sin citas verificables) se descarta")
        void salidaNoEstructurada() {
            llmResponde("Claro, en la crisis hipertensiva se recomienda captopril 25 mg sublingual.");

            consultar(PREGUNTA_1).expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.estado").isEqualTo("RESPUESTA_DESCARTADA")
                    .jsonPath("$.respuesta").value(r -> assertThat((String) r).doesNotContain("captopril"));
        }

        @Test
        @DisplayName("El JSON envuelto en un bloque markdown se acepta")
        void jsonEnBloqueMarkdown() {
            llmResponde("""
                    ```json
                    {"respuesta":"Lactato y hemocultivos antes del antibiótico.",
                     "fuentes":[{"protocolo":"PROTO-002","seccion":"Acciones iniciales"}],"sinInformacion":false}
                    ```
                    """);

            consultar(PREGUNTA_2).expectStatus().isOk()
                    .expectBody().jsonPath("$.estado").isEqualTo("RESPONDIDA");
        }
    }

    @Nested
    @DisplayName("Modo degradado (Resilience4j)")
    class ModoDegradado {

        @Test
        @DisplayName("Si el LLM falla responde 503 con mensaje claro y Retry-After")
        void llmCaido() {
            when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("503 from provider"));

            consultar(PREGUNTA_1)
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectHeader().valueEquals("Retry-After", "30")
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.title").isEqualTo("Asistente clínico no disponible temporalmente")
                    .jsonPath("$.detail").value(d -> assertThat((String) d).contains("triaje"));
        }

        @Test
        @DisplayName("Si el LLM no responde a tiempo, el TimeLimiter corta y responde 503")
        void llmLento() {
            when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
                Thread.sleep(5_000);
                return null;
            });

            consultar(PREGUNTA_1)
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectBody().jsonPath("$.detail")
                    .value(d -> assertThat((String) d).contains("no respondió a tiempo"));
        }

        @Test
        @DisplayName("Tras fallos repetidos el circuito se abre y ya no se llama al proveedor")
        void circuitoAbierto() {
            when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("503 from provider"));
            for (int i = 0; i < 4; i++) {
                consultar(PREGUNTA_1).expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            }
            verify(chatModel, times(4)).call(any(Prompt.class));
            clearInvocations(chatModel);

            consultar(PREGUNTA_2)
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectBody().jsonPath("$.detail")
                    .value(d -> assertThat((String) d).contains("circuito abierto"));
            verify(chatModel, never()).call(any(Prompt.class));
        }

        @Test
        @DisplayName("Con el LLM caído, las preguntas sin protocolo se siguen respondiendo (no lo necesitan)")
        void sinProtocoloFuncionaConLlmCaido() {
            when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("503 from provider"));

            consultar(PREGUNTA_3).expectStatus().isOk()
                    .expectBody().jsonPath("$.estado").isEqualTo("SIN_PROTOCOLO");
        }
    }

    @Nested
    @DisplayName("Validación de entrada")
    class Entrada {

        @Test
        void preguntaVaciaResponde400() {
            consultar("   ").expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.errores[0].campo").isEqualTo("pregunta");
        }

        @Test
        void preguntaDemasiadoLargaResponde400() {
            consultar("¿".repeat(1001)).expectStatus().isBadRequest();
        }

        @Test
        void cuerpoInvalidoResponde400() {
            web.post().uri("/asistente/consulta").contentType(MediaType.APPLICATION_JSON).bodyValue("{pregunta")
                    .exchange().expectStatus().isBadRequest();
        }
    }
}
