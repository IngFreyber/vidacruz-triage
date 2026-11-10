package com.vidacruz.triage.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.IntegracionBase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Publicación de eventos en triaje.eventos vía transactional outbox")
class EventosKafkaIntegrationTest extends IntegracionBase {

    private static final String E1 = """
            {"nombreCompleto":"Paciente Kafka","edad":58,"frecuenciaCardiaca":130,"presionSistolica":180,
             "presionDiastolica":110,"spo2":97,"temperatura":36.8,"dolor":8,"gcs":15,
             "quejaPrincipal":"Dolor torácico"}
            """;

    @Autowired
    WebTestClient web;

    @Autowired
    DatabaseClient db;

    @Autowired
    ObjectMapper mapper;

    @Test
    @DisplayName("Registro y transiciones se publican en orden, con key = pacienteId y headers de tipo")
    void publicaEventosDelCicloDeVidaEnOrden() throws Exception {
        String id = registrar();
        cambiarEstado(id, "EN_CONSULTA");
        cambiarEstado(id, "ALTA");

        List<ConsumerRecord<String, String>> eventos = consumirPorKey(id, 3, Duration.ofSeconds(20));

        assertThat(eventos).extracting(r -> header(r, "eventType"))
                .containsExactly("paciente_registrado", "paciente_en_consulta", "paciente_alta");
        assertThat(eventos).extracting(ConsumerRecord::partition).containsOnly(eventos.get(0).partition());

        JsonNode registrado = mapper.readTree(eventos.get(0).value());
        assertThat(registrado.get("eventId").asText()).isEqualTo(header(eventos.get(0), "eventId"));
        assertThat(registrado.get("eventType").asText()).isEqualTo("paciente_registrado");
        assertThat(registrado.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(registrado.get("pacienteId").asText()).isEqualTo(id);
        assertThat(registrado.get("nivel").asText()).isEqualTo("CRITICO");
        assertThat(registrado.get("estadoNuevo").asText()).isEqualTo("REGISTRADO");
        assertThat(registrado.has("estadoAnterior")).isFalse();
        assertThat(registrado.get("slaMinutos").asInt()).isEqualTo(5);
        assertThat(registrado.get("nombreCompleto").asText()).isEqualTo("Paciente Kafka");

        JsonNode alta = mapper.readTree(eventos.get(2).value());
        assertThat(alta.get("estadoAnterior").asText()).isEqualTo("EN_CONSULTA");
        assertThat(alta.get("estadoNuevo").asText()).isEqualTo("ALTA");
    }

    @Test
    @DisplayName("Trazado distribuido: el evento lleva el traceparent W3C del request (mismo traceId que X-Trace-Id)")
    void propagaLaTrazaDelRequestAKafka() {
        var resultado = web.post().uri("/triage/pacientes").contentType(MediaType.APPLICATION_JSON).bodyValue(E1)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult();
        String traceId = resultado.getResponseHeaders().getFirst("X-Trace-Id");
        String id = (String) resultado.getResponseBody().get("id");

        ConsumerRecord<String, String> evento = consumirPorKey(id, 1, Duration.ofSeconds(20)).get(0);

        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(header(evento, "traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]")
                .startsWith("00-" + traceId + "-");
    }

    @Test
    @DisplayName("Tras el ack de Kafka los eventos quedan marcados como publicados en el outbox")
    void marcaLosEventosComoPublicados() {
        String id = registrar();
        consumirPorKey(id, 1, Duration.ofSeconds(20));

        Instant limite = Instant.now().plusSeconds(10);
        Long pendientes;
        do {
            pendientes = db.sql("SELECT count(*) AS n FROM outbox_eventos WHERE agregado_id = :id "
                            + "AND publicado_en IS NULL")
                    .bind("id", UUID.fromString(id))
                    .map(r -> r.get("n", Long.class)).one().block();
        } while (pendientes != null && pendientes > 0 && Instant.now().isBefore(limite));

        assertThat(pendientes).isZero();
    }

    private String registrar() {
        Map<?, ?> body = web.post().uri("/triage/pacientes").contentType(MediaType.APPLICATION_JSON).bodyValue(E1)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("id");
    }

    private void cambiarEstado(String id, String estado) {
        web.patch().uri("/triage/pacientes/{id}/estado", id).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("estado", estado)).exchange().expectStatus().isOk();
    }

    private static List<ConsumerRecord<String, String>> consumirPorKey(String key, int esperados, Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> encontrados = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(TOPIC));
            Instant limite = Instant.now().plus(timeout);
            while (encontrados.size() < esperados && Instant.now().isBefore(limite)) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(r.key())) {
                        encontrados.add(r);
                    }
                }
            }
        }
        assertThat(encontrados).as("eventos recibidos para key %s", key).hasSize(esperados);
        return encontrados;
    }

    private static String header(ConsumerRecord<String, String> r, String nombre) {
        return new String(r.headers().lastHeader(nombre).value(), StandardCharsets.UTF_8);
    }
}
