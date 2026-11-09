package com.vidacruz.asistente;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.util.List;
import java.util.Map;

/**
 * Base de integración del asistente: Redis y Kafka reales en Testcontainers (singleton por JVM).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public abstract class IntegracionBase {

    protected static final String TOPIC = "triaje.eventos";

    /**
     * El LLM siempre está simulado en los tests: no se requiere API key ni red, y cada test controla qué
     * "responde" el modelo. Los embeddings y la recuperación sí son reales (modelo ONNX local).
     */
    @MockitoBean
    protected ChatModel chatModel;

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        REDIS.start();
        KAFKA.start();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo crear el topic de pruebas", e);
        }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("asistente.urgencias.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("resilience4j.timelimiter.instances.llm.timeout-duration", () -> "2s");
        r.add("resilience4j.circuitbreaker.instances.llm.wait-duration-in-open-state", () -> "60s");
    }
}
