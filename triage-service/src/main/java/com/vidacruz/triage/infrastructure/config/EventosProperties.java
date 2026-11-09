package com.vidacruz.triage.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuración de la publicación de eventos hacia Kafka.
 *
 * @param topic            topic de destino
 * @param bootstrapServers brokers Kafka
 * @param outbox           parámetros del relay del outbox
 */
@ConfigurationProperties(prefix = "triage.eventos")
public record EventosProperties(
        @DefaultValue("triaje.eventos") String topic,
        @DefaultValue("localhost:9092") String bootstrapServers,
        @DefaultValue Outbox outbox) {

    /**
     * @param habilitado si el relay publica (se puede apagar en tests aislados)
     * @param intervalo  cada cuánto se revisan eventos pendientes
     * @param tamanoLote máximo de eventos por lote (acota memoria y la duración de la transacción)
     */
    public record Outbox(
            @DefaultValue("true") boolean habilitado,
            @DefaultValue("500ms") Duration intervalo,
            @DefaultValue("100") int tamanoLote) {
    }
}
