package com.vidacruz.asistente.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.asistente.application.service.EstadoUrgenciasService;
import com.vidacruz.asistente.domain.urgencias.EventoTriaje;
import com.vidacruz.asistente.domain.urgencias.NivelTriage;
import com.vidacruz.asistente.infrastructure.config.VistaUrgenciasProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumidor reactivo de {@code triaje.eventos} que mantiene la vista de urgencias.
 *
 * <ul>
 *   <li><b>Orden</b>: {@code groupBy(partición)} + {@code concatMap}: los eventos de una partición (y por tanto
 *       de un mismo paciente, key = pacienteId) se aplican de a uno y en orden; las particiones avanzan en
 *       paralelo.</li>
 *   <li><b>At-least-once</b>: el offset se confirma solo después de aplicar en Redis; las reentregas las
 *       absorbe la deduplicación por eventId.</li>
 *   <li><b>Backpressure</b>: si Redis no responde, el registro se reintenta con backoff y la partición deja de
 *       pedir mensajes; reactor-kafka pausa el consumo en lugar de acumularlos en memoria.</li>
 *   <li><b>Poison messages</b>: un mensaje fuera de contrato se registra y se descarta (no se reintenta).</li>
 * </ul>
 */
@Component
public class ConsumidorEventosTriaje implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorEventosTriaje.class);
    private static final int MAX_PARTICIONES_EN_PARALELO = 256;

    private final EstadoUrgenciasService urgencias;
    private final VistaUrgenciasProperties props;
    private final ObjectMapper mapper;

    private volatile Disposable suscripcion;

    public ConsumidorEventosTriaje(EstadoUrgenciasService urgencias, VistaUrgenciasProperties props,
                                   ObjectMapper mapper) {
        this.urgencias = urgencias;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public void start() {
        if (!props.habilitado()) {
            log.info("Consumidor de triaje.eventos deshabilitado por configuración");
            return;
        }
        suscripcion = Flux.defer(() -> KafkaReceiver.create(opciones()).receive())
                .groupBy(r -> r.receiverOffset().topicPartition())
                .flatMap(particion -> particion.concatMap(this::procesar), MAX_PARTICIONES_EN_PARALELO)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(s -> log.warn("Consumidor de triaje.eventos reiniciándose tras error: {}",
                                s.failure().toString())))
                .subscribe();
        log.info("Consumidor de {} iniciado (grupo {})", props.topic(), props.grupoConsumidor());
    }

    Mono<Void> procesar(ReceiverRecord<String, String> registro) {
        return Mono.fromCallable(() -> parsear(registro.value()))
                .flatMap(urgencias::aplicar)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(500))
                        .maxBackoff(Duration.ofSeconds(30))
                        .filter(e -> !(e instanceof MensajeInvalidoException))
                        .doBeforeRetry(s -> log.warn("No se pudo aplicar el evento (offset {}); reintentando: {}",
                                registro.receiverOffset().offset(), s.failure().toString())))
                .onErrorResume(MensajeInvalidoException.class, e -> {
                    log.error("Mensaje descartado de {}-{} offset {}: {}", registro.topic(),
                            registro.partition(), registro.offset(), e.getMessage());
                    return Mono.empty();
                })
                .then(Mono.fromRunnable(() -> registro.receiverOffset().acknowledge()));
    }

    EventoTriaje parsear(String json) {
        try {
            EventoTriajeMensaje m = mapper.readValue(json, EventoTriajeMensaje.class);
            return new EventoTriaje(m.eventId(), m.eventType(), m.pacienteId(),
                    m.nivel() == null ? null : NivelTriage.valueOf(m.nivel()), m.estadoNuevo(), m.occurredAt());
        } catch (Exception e) {
            throw new MensajeInvalidoException("Evento fuera de contrato: " + e.getMessage(), e);
        }
    }

    private ReceiverOptions<String, String> opciones() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, props.grupoConsumidor());
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "asistente-vista-urgencias");
        // Primer arranque: reconstruye la vista desde el inicio del topic.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
        return ReceiverOptions.<String, String>create(config)
                .commitInterval(Duration.ofSeconds(1))
                .commitBatchSize(100)
                .addAssignListener(p -> log.info("Particiones asignadas: {}", p))
                .addRevokeListener(p -> log.info("Particiones revocadas: {}", p))
                .subscription(List.of(props.topic()));
    }

    @Override
    public void stop() {
        if (suscripcion != null) {
            suscripcion.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        return suscripcion != null && !suscripcion.isDisposed();
    }
}
