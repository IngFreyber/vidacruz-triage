package com.vidacruz.asistente.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.asistente.IntegracionBase;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * De punta a punta: eventos publicados en triaje.eventos (como lo haría triage-service) → consumidor reactivo
 * → Redis → {@code GET /asistente/estado-urgencias}. El asistente nunca llama a la API de triaje.
 */
@DisplayName("Estado de urgencias reconstruido desde triaje.eventos (RF-04 B)")
class EstadoUrgenciasKafkaIntegrationTest extends IntegracionBase {

    @Autowired
    WebTestClient web;

    @Autowired
    ObjectMapper mapper;

    private JsonNode estado() {
        return web.get().uri("/asistente/estado-urgencias").exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private static long nivel(JsonNode estado, String nivel) {
        return estado.get("porNivel").get(nivel).asLong();
    }

    private static String evento(UUID eventId, String tipo, UUID paciente, String nivel, String estado, int seg) {
        return """
                {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"%s","pacienteId":"%s",
                 "nombreCompleto":"Paciente Kafka","nivel":"%s","estadoNuevo":"%s","escalado":false,"slaMinutos":5}
                """.formatted(eventId, tipo, Instant.parse("2026-11-09T10:00:00Z").plusSeconds(seg), paciente,
                nivel, estado);
    }

    @Test
    @DisplayName("Aplica el flujo, ignora duplicados y descarta mensajes fuera de contrato sin bloquearse")
    void reconstruyeLaVistaDesdeKafka() throws Exception {
        JsonNode inicio = estado();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID registroB = UUID.randomUUID();

        try (KafkaProducer<String, String> productor = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            enviar(productor, a, evento(UUID.randomUUID(), "paciente_registrado", a, "URGENTE", "REGISTRADO", 0));
            enviar(productor, b, evento(registroB, "paciente_registrado", b, "CRITICO", "REGISTRADO", 1));
            enviar(productor, b, "{ mensaje corrupto");
            enviar(productor, b, evento(UUID.randomUUID(), "paciente_en_consulta", b, "CRITICO", "EN_CONSULTA", 2));
            enviar(productor, a, evento(UUID.randomUUID(), "paciente_alta", a, "URGENTE", "ALTA", 3));
            // Reentrega del registro de B (at-least-once): no debe contarse otra vez.
            enviar(productor, b, evento(registroB, "paciente_registrado", b, "CRITICO", "REGISTRADO", 1));
            productor.flush();
        }

        Predicate<JsonNode> esperado = e -> nivel(e, "CRITICO") - nivel(inicio, "CRITICO") == 1
                && nivel(e, "URGENTE") - nivel(inicio, "URGENTE") == 0
                && e.get("totalActivos").asLong() - inicio.get("totalActivos").asLong() == 1;
        JsonNode fin = esperarHasta(esperado);

        assertThat(esperado.test(fin)).as("estado final: %s (inicio %s)", fin, inicio).isTrue();
        assertThat(fin.get("fuente").asText()).isEqualTo("kafka:triaje.eventos");
        assertThat(fin.get("actualizadoEn").isNull()).isFalse();

        // Estable: ningún reprocesamiento posterior cambia el resultado.
        Thread.sleep(1_500);
        assertThat(esperado.test(estado())).isTrue();
    }

    private JsonNode esperarHasta(Predicate<JsonNode> condicion) throws InterruptedException {
        Instant limite = Instant.now().plusSeconds(20);
        JsonNode actual = estado();
        while (!condicion.test(actual) && Instant.now().isBefore(limite)) {
            Thread.sleep(250);
            actual = estado();
        }
        return actual;
    }

    private static void enviar(KafkaProducer<String, String> productor, UUID key, String valor) throws Exception {
        productor.send(new ProducerRecord<>(TOPIC, key.toString(), valor)).get();
    }
}
