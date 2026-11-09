package com.vidacruz.asistente.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuración de la vista de urgencias (RF-04 parte B).
 *
 * @param topic            topic de eventos de triaje
 * @param bootstrapServers brokers Kafka
 * @param grupoConsumidor  consumer group (las réplicas del asistente se reparten las particiones)
 * @param habilitado       si el consumidor arranca
 * @param ttlDeduplicacion cuánto se recuerda un eventId ya procesado
 * @param ttlPacienteAlta  cuánto se conserva el estado de un paciente dado de alta (rechaza eventos tardíos)
 */
@ConfigurationProperties(prefix = "asistente.urgencias")
public record VistaUrgenciasProperties(
        @DefaultValue("triaje.eventos") String topic,
        @DefaultValue("localhost:9092") String bootstrapServers,
        @DefaultValue("asistente-vista-urgencias") String grupoConsumidor,
        @DefaultValue("true") boolean habilitado,
        @DefaultValue("7d") Duration ttlDeduplicacion,
        @DefaultValue("7d") Duration ttlPacienteAlta) {
}
