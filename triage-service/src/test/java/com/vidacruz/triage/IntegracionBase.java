package com.vidacruz.triage;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.web.reactive.server.WebTestClientBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.util.List;
import java.util.Map;

/**
 * Base de los tests de integración: PostgreSQL y Kafka reales en Testcontainers (mismos motores que
 * producción), compartidos por todas las clases (patrón singleton container) para reutilizar el contexto.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
// Habilita métricas y trazas en tests (Spring Boot las desactiva por defecto en @SpringBootTest).
@AutoConfigureObservability
// El monitor de SLA se apaga en el contexto compartido: los tests invocan el caso de uso con un "ahora"
// controlado. MonitorSlaIntegrationTest lo enciende en su propio contexto.
@TestPropertySource(properties = "triage.escalamiento.habilitado=false")
@Import(IntegracionBase.ClienteAutenticado.class)
public abstract class IntegracionBase {

    protected static final String TOPIC = "triaje.eventos";
    /** API key de pruebas (no es un secreto: solo existe en el contexto de tests). */
    protected static final String API_KEY = "clave-de-pruebas-triage-0123456789";
    protected static final String HEADER_API_KEY = "X-API-Key";

    /** Todas las peticiones de WebTestClient llevan la API key; los tests de seguridad la omiten a propósito. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ClienteAutenticado {
        @Bean
        WebTestClientBuilderCustomizer apiKeyPorDefecto() {
            return builder -> builder.defaultHeader(HEADER_API_KEY, API_KEY);
        }
    }

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("triage")
            .withUsername("triage")
            .withPassword("triage_test");

    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        SoporteBlockHound.instalar();
        POSTGRES.start();
        KAFKA.start();
        crearTopic();
    }

    private static void crearTopic() {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo crear el topic de pruebas", e);
        }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        String hostPuerto = POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
        r.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + hostPuerto + "/" + POSTGRES.getDatabaseName());
        r.add("spring.r2dbc.username", POSTGRES::getUsername);
        r.add("spring.r2dbc.password", POSTGRES::getPassword);
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("triage.eventos.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("triage.eventos.outbox.intervalo", () -> "200ms");
        r.add("seguridad.api-keys", () -> API_KEY);
    }
}
