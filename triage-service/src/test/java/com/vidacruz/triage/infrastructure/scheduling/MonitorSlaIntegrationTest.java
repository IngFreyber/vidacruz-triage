package com.vidacruz.triage.infrastructure.scheduling;

import com.vidacruz.triage.IntegracionBase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba de punta a punta del proceso de fondo: SLA y frecuencia configurables (RF-03) permiten observar el
 * escalamiento real en segundos, sin invocar el caso de uso manualmente.
 */
@DisplayName("Monitor de SLA de punta a punta: scheduler → PostgreSQL → Kafka")
@TestPropertySource(properties = {
        "triage.escalamiento.habilitado=true",
        "triage.escalamiento.intervalo=300ms",
        "triage.sla.critico=1s"})
class MonitorSlaIntegrationTest extends IntegracionBase {

    private static final String E6 = """
            {"nombreCompleto":"Paciente Monitor","edad":70,"frecuenciaCardiaca":35,"presionSistolica":80,
             "presionDiastolica":50,"spo2":85,"temperatura":35.9,"dolor":0,"gcs":6,"quejaPrincipal":"Inconsciente"}
            """;

    @Autowired
    WebTestClient web;

    @Test
    void escalaAutomaticamenteUnaSolaVezYPublicaElEvento() throws InterruptedException {
        Map<?, ?> body = web.post().uri("/triage/pacientes").contentType(MediaType.APPLICATION_JSON).bodyValue(E6)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String id = (String) body.get("id");

        Instant limite = Instant.now().plusSeconds(15);
        boolean escalado = false;
        while (!escalado && Instant.now().isBefore(limite)) {
            Thread.sleep(250);
            escalado = Boolean.TRUE.equals(web.get().uri("/triage/pacientes/{id}", id).exchange()
                    .expectBody(Map.class).returnResult().getResponseBody()
                    .get("flags") instanceof Map<?, ?> flags ? flags.get("escalado") : null);
        }
        assertThat(escalado).as("el monitor debe escalar al paciente CRITICO con SLA de 1s").isTrue();

        // Varios ciclos más del monitor no deben volver a escalarlo.
        Thread.sleep(1_500);
        web.get().uri("/triage/pacientes/{id}/auditoria", id).exchange()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[1].tipoEvento").isEqualTo("paciente_escalado");

        assertThat(eventosEscaladoEnKafka(id)).isEqualTo(1);
    }

    private static long eventosEscaladoEnKafka(String id) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        long encontrados = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(TOPIC));
            Instant limite = Instant.now().plusSeconds(8);
            while (Instant.now().isBefore(limite)) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (id.equals(r.key()) && "paciente_escalado".equals(
                            new String(r.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))) {
                        encontrados++;
                    }
                }
            }
        }
        return encontrados;
    }
}
