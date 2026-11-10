package com.vidacruz.alertas.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.alertas.IntegracionBase;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RF-06: alertas de escalamiento al equipo médico")
class AlertasIntegrationTest extends IntegracionBase {

    private static KafkaProducer<String, String> productor;

    @Autowired
    WebTestClient web;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @LocalServerPort
    int puerto;

    @BeforeAll
    static void crearProductor() {
        productor = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    @AfterAll
    static void cerrarProductor() {
        productor.close();
    }

    @BeforeEach
    void reiniciarTeams() {
        TEAMS_STATUS.set(200);
        circuitBreakers.circuitBreaker("teams").reset();
    }

    private static void publicar(UUID eventId, String tipo, UUID paciente, String nombre) throws Exception {
        String json = """
                {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"2026-11-09T10:07:00Z",
                 "pacienteId":"%s","nombreCompleto":"%s","quejaPrincipal":"Dolor torácico","nivel":"CRITICO",
                 "estadoAnterior":"REGISTRADO","estadoNuevo":"REGISTRADO","escalado":true,"slaMinutos":5,
                 "fechaRegistro":"2026-11-09T10:00:00Z"}
                """.formatted(eventId, tipo, paciente, nombre);
        ProducerRecord<String, String> registro = new ProducerRecord<>(TOPIC, paciente.toString(), json);
        registro.headers().add(new RecordHeader("eventType", tipo.getBytes(StandardCharsets.UTF_8)));
        productor.send(registro).get();
    }

    private JsonNode alerta(UUID id) {
        return web.get().uri("/alertas/{id}", id).exchange()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private static <T> T esperar(Supplier<T> valor, java.util.function.Predicate<T> hasta) throws InterruptedException {
        Instant limite = Instant.now().plusSeconds(20);
        T actual = valor.get();
        while (!hasta.test(actual) && Instant.now().isBefore(limite)) {
            Thread.sleep(200);
            actual = valor.get();
        }
        return actual;
    }

    private static long tarjetasTeamsDe(UUID alerta) {
        return TEAMS_RECIBIDOS.stream().filter(b -> b.contains(alerta.toString())).count();
    }

    @Test
    @DisplayName("Un escalamiento genera una alerta PENDIENTE en el historial y una tarjeta en Teams")
    void escalamientoGeneraAlerta() throws Exception {
        UUID evento = UUID.randomUUID();
        UUID paciente = UUID.randomUUID();

        publicar(evento, "paciente_escalado", paciente, "Pedro Ruiz");

        JsonNode a = esperar(() -> alerta(evento), n -> n != null && n.has("id"));
        assertThat(a.get("estado").asText()).isEqualTo("PENDIENTE");
        assertThat(a.get("pacienteId").asText()).isEqualTo(paciente.toString());
        assertThat(a.get("minutosEspera").asLong()).isEqualTo(7);
        assertThat(a.get("mensaje").asText()).contains("Pedro Ruiz").contains("SLA 5 min");

        long tarjetas = esperar(() -> tarjetasTeamsDe(evento), n -> n >= 1);
        assertThat(tarjetas).isEqualTo(1);
        JsonNode tarjeta = mapper.readTree(TEAMS_RECIBIDOS.stream().filter(b -> b.contains(evento.toString()))
                .findFirst().orElseThrow());
        assertThat(tarjeta.get("type").asText()).isEqualTo("message");
        assertThat(tarjeta.at("/attachments/0/contentType").asText())
                .isEqualTo("application/vnd.microsoft.card.adaptive");
        assertThat(tarjeta.at("/attachments/0/content/body/0/text").asText()).contains("CRITICO");

        web.get().uri("/alertas?estado=PENDIENTE").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[?(@.id == '%s')]".formatted(evento)).exists();
    }

    @Test
    @DisplayName("Una reentrega del mismo evento no duplica la alerta ni el aviso a Teams")
    void reentregaIdempotente() throws Exception {
        UUID evento = UUID.randomUUID();
        UUID paciente = UUID.randomUUID();

        publicar(evento, "paciente_escalado", paciente, "Ana Gil");
        publicar(evento, "paciente_escalado", paciente, "Ana Gil");
        esperar(() -> alerta(evento), n -> n != null && n.has("id"));
        Thread.sleep(1_500);

        assertThat(tarjetasTeamsDe(evento)).isEqualTo(1);
        long enHistorial = web.get().uri("/alertas?limite=200").exchange()
                .expectBodyList(JsonNode.class).returnResult().getResponseBody().stream()
                .filter(n -> n.get("id").asText().equals(evento.toString())).count();
        assertThat(enHistorial).isEqualTo(1);
    }

    @Test
    @DisplayName("Los eventos que no son escalamientos no generan alertas")
    void otrosEventosSeIgnoran() throws Exception {
        UUID evento = UUID.randomUUID();
        publicar(evento, "paciente_registrado", UUID.randomUUID(), "Sin Alerta");
        UUID marcador = UUID.randomUUID();
        publicar(marcador, "paciente_escalado", UUID.randomUUID(), "Marcador");
        esperar(() -> alerta(marcador), n -> n != null && n.has("id"));

        web.get().uri("/alertas/{id}", evento).exchange().expectStatus().isNotFound();
        assertThat(tarjetasTeamsDe(evento)).isZero();
    }

    @Test
    @DisplayName("Con Teams caído la alerta igual queda registrada; se reintenta y no bloquea al consumidor")
    void teamsCaido() throws Exception {
        TEAMS_STATUS.set(500);
        UUID evento = UUID.randomUUID();

        publicar(evento, "paciente_escalado", UUID.randomUUID(), "Luis Mora");

        JsonNode a = esperar(() -> alerta(evento), n -> n != null && n.has("id"));
        assertThat(a.get("estado").asText()).isEqualTo("PENDIENTE");
        long intentos = esperar(() -> tarjetasTeamsDe(evento), n -> n >= 3);
        assertThat(intentos).as("3 intentos (retry) antes de rendirse").isEqualTo(3);

        TEAMS_STATUS.set(200);
        UUID siguiente = UUID.randomUUID();
        publicar(siguiente, "paciente_escalado", UUID.randomUUID(), "Siguiente");
        assertThat(esperar(() -> alerta(siguiente), n -> n != null && n.has("id"))).isNotNull();
    }

    @Test
    @DisplayName("SSE: un tablero conectado recibe la alerta nueva y luego su atención en tiempo real")
    void streamSse() throws Exception {
        List<ServerSentEvent<Map<String, Object>>> recibidos = new CopyOnWriteArrayList<>();
        Disposable conexion = WebClient.create("http://localhost:" + puerto).get()
                .uri("/alertas/stream?pendientes=false&api_key=" + API_KEY)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<Map<String, Object>>>() {
                })
                .subscribe(recibidos::add);
        try {
            Thread.sleep(1_500); // suscripción establecida (incluye el keep-alive inicial)
            assertThat(recibidos).as("llegan comentarios keep-alive").isNotEmpty();

            UUID evento = UUID.randomUUID();
            publicar(evento, "paciente_escalado", UUID.randomUUID(), "Rosa Díaz");
            esperar(() -> recibidos.stream().anyMatch(e -> "alerta".equals(e.event())), b -> b);

            ServerSentEvent<Map<String, Object>> nueva = recibidos.stream()
                    .filter(e -> "alerta".equals(e.event())).findFirst().orElseThrow();
            assertThat(nueva.id()).isEqualTo(evento.toString());
            assertThat(nueva.data()).containsEntry("nombrePaciente", "Rosa Díaz").containsEntry("estado", "PENDIENTE");

            web.post().uri("/alertas/{id}/atencion", evento).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("atendidaPor", "Dra. Salas")).exchange().expectStatus().isOk();
            esperar(() -> recibidos.stream().anyMatch(e -> "alerta_atendida".equals(e.event())), b -> b);

            assertThat(recibidos.stream().filter(e -> "alerta_atendida".equals(e.event())).findFirst().orElseThrow()
                    .data()).containsEntry("atendidaPor", "Dra. Salas");
        } finally {
            conexion.dispose();
        }
    }

    @Test
    @DisplayName("Regresión: tras desconectarse todos los tableros, uno nuevo sigue recibiendo alertas")
    void sseSobreviveALaDesconexionDeTodosLosClientes() throws Exception {
        WebClient cliente = WebClient.create("http://localhost:" + puerto);
        // 1) Un tablero se conecta y se desconecta (el flujo compartido queda sin suscriptores).
        cliente.get().uri("/alertas/stream?pendientes=false&api_key=" + API_KEY).retrieve()
                .bodyToFlux(String.class).take(Duration.ofMillis(800)).blockLast();

        // 2) Un tablero nuevo debe seguir recibiendo alertas en vivo.
        List<ServerSentEvent<Map<String, Object>>> recibidos = new CopyOnWriteArrayList<>();
        Disposable conexion = cliente.get().uri("/alertas/stream?pendientes=false&api_key=" + API_KEY)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<Map<String, Object>>>() {
                })
                .subscribe(recibidos::add);
        try {
            Thread.sleep(1_500);
            UUID evento = UUID.randomUUID();
            publicar(evento, "paciente_escalado", UUID.randomUUID(), "Tras Reconexión");

            boolean llego = esperar(() -> recibidos.stream()
                    .anyMatch(e -> evento.toString().equals(e.id())), b -> b);
            assertThat(llego).isTrue();
        } finally {
            conexion.dispose();
        }
    }

    @Nested
    @DisplayName("Atención de alertas (acknowledge)")
    class Atencion {

        private UUID alertaPendiente() throws Exception {
            UUID evento = UUID.randomUUID();
            publicar(evento, "paciente_escalado", UUID.randomUUID(), "Paciente Ack");
            esperar(() -> alerta(evento), n -> n != null && n.has("id"));
            return evento;
        }

        @Test
        @DisplayName("La primera confirmación gana; una segunda no cambia quién la tomó")
        void primeraConfirmacionGana() throws Exception {
            UUID id = alertaPendiente();

            web.post().uri("/alertas/{id}/atencion", id).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("atendidaPor", "Dr. Pérez")).exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.estado").isEqualTo("ATENDIDA")
                    .jsonPath("$.atendidaPor").isEqualTo("Dr. Pérez")
                    .jsonPath("$.atendidaEn").isNotEmpty();

            web.post().uri("/alertas/{id}/atencion", id).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("atendidaPor", "Dra. Gómez")).exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.atendidaPor").isEqualTo("Dr. Pérez");

            web.get().uri("/alertas?estado=PENDIENTE&limite=200").exchange()
                    .expectBody().jsonPath("$[?(@.id == '%s')]".formatted(id)).doesNotExist();
        }

        @Test
        void alertaInexistenteResponde404() {
            web.post().uri("/alertas/{id}/atencion", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("atendidaPor", "Dr. Pérez")).exchange()
                    .expectStatus().isNotFound();
        }

        @Test
        void sinResponsableResponde400() throws Exception {
            UUID id = alertaPendiente();

            web.post().uri("/alertas/{id}/atencion", id).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("atendidaPor", " ")).exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.errores[0].campo").isEqualTo("atendidaPor");
        }

        @Test
        void estadoDeFiltroInvalidoResponde400() {
            web.get().uri("/alertas?estado=OLVIDADA").exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.errores[0].campo").isEqualTo("estado");
        }

        @Test
        void limiteFueraDeRangoResponde400() {
            web.get().uri("/alertas?limite=0").exchange().expectStatus().isBadRequest();
        }
    }

    @Test
    void streamResponderConTipoEventStream() {
        web.get().uri("/alertas/stream").accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class).getResponseBody().take(Duration.ofMillis(700)).blockLast();
    }
}
