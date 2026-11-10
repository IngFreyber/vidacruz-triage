package com.vidacruz.alertas;

import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base de integración de alertas: Redis y Kafka reales (Testcontainers) y un webhook de Teams simulado con el
 * {@link HttpServer} del JDK, que registra cada tarjeta recibida y permite simular caídas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public abstract class IntegracionBase {

    protected static final String TOPIC = "triaje.eventos";

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    /** Cuerpos recibidos por el webhook simulado de Teams. */
    protected static final List<String> TEAMS_RECIBIDOS = new CopyOnWriteArrayList<>();
    /** Código HTTP que responde el webhook simulado (200 = Teams operativo). */
    protected static final AtomicInteger TEAMS_STATUS = new AtomicInteger(200);
    static final HttpServer TEAMS;

    static {
        REDIS.start();
        KAFKA.start();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo crear el topic de pruebas", e);
        }
        try {
            TEAMS = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        TEAMS.createContext("/webhook/token-secreto", intercambio -> {
            TEAMS_RECIBIDOS.add(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            intercambio.sendResponseHeaders(TEAMS_STATUS.get(), -1);
            intercambio.close();
        });
        TEAMS.start();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("alertas.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("alertas.offset-inicial", () -> "earliest");
        r.add("alertas.heartbeat", () -> "500ms");
        r.add("alertas.teams.webhook-url",
                () -> "http://localhost:" + TEAMS.getAddress().getPort() + "/webhook/token-secreto");
        r.add("resilience4j.retry.instances.teams.wait-duration", () -> "50ms");
    }
}
