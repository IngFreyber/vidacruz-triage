package com.vidacruz.alertas.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuración del servicio de alertas (RF-06).
 *
 * @param topic            topic de eventos de triaje
 * @param bootstrapServers brokers Kafka
 * @param grupoConsumidor  consumer group
 * @param habilitado       si el consumidor arranca
 * @param offsetInicial    dónde empieza un consumer group nuevo: {@code latest} (por defecto, no re-alerta
 *                         escalamientos históricos al desplegar) o {@code earliest}
 * @param retencion        cuánto se conserva el historial de alertas
 * @param heartbeat        intervalo del comentario keep-alive en las conexiones SSE
 * @param teams            canal Microsoft Teams (opcional)
 */
@ConfigurationProperties(prefix = "alertas")
public record AlertasProperties(
        @DefaultValue("triaje.eventos") String topic,
        @DefaultValue("localhost:9092") String bootstrapServers,
        @DefaultValue("alertas-escalamientos") String grupoConsumidor,
        @DefaultValue("true") boolean habilitado,
        @DefaultValue("latest") String offsetInicial,
        @DefaultValue("30d") Duration retencion,
        @DefaultValue("15s") Duration heartbeat,
        @DefaultValue Teams teams) {

    /**
     * @param webhookUrl URL del webhook de Workflows; vacía = canal deshabilitado
     * @param timeout    tiempo máximo por intento
     */
    public record Teams(@DefaultValue("") String webhookUrl, @DefaultValue("5s") Duration timeout) {

        public boolean habilitado() {
            return webhookUrl != null && !webhookUrl.isBlank();
        }
    }
}
