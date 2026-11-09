package com.vidacruz.triage.infrastructure.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Productor Kafka reactivo. Garantías:
 * <ul>
 *   <li>{@code acks=all} + {@code enable.idempotence=true}: sin pérdidas ni duplicados por reintento del productor
 *       y con orden por partición preservado (max.in.flight ≤ 5).</li>
 *   <li>El productor se crea de forma perezosa: el servicio arranca aunque Kafka no esté disponible
 *       (los eventos esperan en el outbox).</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(EventosProperties.class)
public class KafkaConfig {

    @Bean(destroyMethod = "close")
    KafkaSender<String, String> kafkaSender(EventosProperties props) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.bootstrapServers());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "triage-service-outbox");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);

        SenderOptions<String, String> opciones = SenderOptions.<String, String>create(config)
                // Si un envío falla se detiene el lote: lo no confirmado queda pendiente en el outbox,
                // conservando el orden por paciente.
                .stopOnError(true);
        return KafkaSender.create(opciones);
    }
}
