package com.vidacruz.alertas.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.alertas.application.service.ProcesarEscalamientoService;
import com.vidacruz.alertas.domain.EscalamientoPaciente;
import com.vidacruz.alertas.infrastructure.config.AlertasProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.header.Header;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumidor reactivo de {@code triaje.eventos} que solo atiende {@code paciente_escalado}.
 * Los demás tipos se filtran por el header {@code eventType} sin deserializar el cuerpo.
 * Mismas garantías que el consumidor del asistente: orden por partición, offset confirmado tras procesar,
 * reintento con backoff ante fallas transitorias (Redis) y descarte de mensajes fuera de contrato.
 */
@Component
public class ConsumidorEscalamientos implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorEscalamientos.class);
    static final String TIPO_ESCALADO = "paciente_escalado";

    private final ProcesarEscalamientoService procesar;
    private final AlertasProperties props;
    private final ObjectMapper mapper;

    private volatile Disposable suscripcion;

    public ConsumidorEscalamientos(ProcesarEscalamientoService procesar, AlertasProperties props,
                                   ObjectMapper mapper) {
        this.procesar = procesar;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public void start() {
        if (!props.habilitado()) {
            log.info("Consumidor de escalamientos deshabilitado por configuración");
            return;
        }
        suscripcion = Flux.defer(() -> KafkaReceiver.create(opciones()).receive())
                .groupBy(r -> r.receiverOffset().topicPartition())
                .flatMap(particion -> particion.concatMap(this::procesar), 256)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(s -> log.warn("Consumidor de escalamientos reiniciándose: {}",
                                s.failure().toString())))
                .subscribe();
        log.info("Consumidor de escalamientos iniciado sobre {} (grupo {})", props.topic(), props.grupoConsumidor());
    }

    Mono<Void> procesar(ReceiverRecord<String, String> registro) {
        Header tipo = registro.headers().lastHeader("eventType");
        if (tipo != null && !TIPO_ESCALADO.equals(new String(tipo.value(), StandardCharsets.UTF_8))) {
            return Mono.fromRunnable(() -> registro.receiverOffset().acknowledge());
        }
        return Mono.fromCallable(() -> parsear(registro.value()))
                .flatMap(e -> e == null ? Mono.empty() : procesar.procesar(e))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(500))
                        .maxBackoff(Duration.ofSeconds(30))
                        .filter(e -> !(e instanceof MensajeInvalidoException))
                        .doBeforeRetry(s -> log.warn("No se pudo procesar el escalamiento (offset {}); reintentando: {}",
                                registro.offset(), s.failure().toString())))
                .onErrorResume(MensajeInvalidoException.class, e -> {
                    log.error("Mensaje descartado de {}-{} offset {}: {}", registro.topic(), registro.partition(),
                            registro.offset(), e.getMessage());
                    return Mono.empty();
                })
                .then(Mono.fromRunnable(() -> registro.receiverOffset().acknowledge()));
    }

    /**
     * @return el escalamiento, o {@code null} si el evento es de otro tipo (mensajes sin header)
     */
    EscalamientoPaciente parsear(String json) {
        EventoTriajeMensaje m;
        try {
            m = mapper.readValue(json, EventoTriajeMensaje.class);
        } catch (Exception e) {
            throw new MensajeInvalidoException("JSON inválido: " + e.getMessage(), e);
        }
        if (!TIPO_ESCALADO.equals(m.eventType())) {
            return null;
        }
        try {
            return new EscalamientoPaciente(m.eventId(), m.pacienteId(), m.nombreCompleto(), m.quejaPrincipal(),
                    m.nivel(), m.slaMinutos() == null ? 0 : m.slaMinutos(), m.fechaRegistro(), m.occurredAt());
        } catch (RuntimeException e) {
            throw new MensajeInvalidoException("Escalamiento fuera de contrato: " + e.getMessage(), e);
        }
    }

    private ReceiverOptions<String, String> opciones() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, props.grupoConsumidor());
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "alertas-escalamientos");
        // Primer arranque (por defecto): solo eventos nuevos. Re-alertar escalamientos históricos sería ruido.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, props.offsetInicial());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return ReceiverOptions.<String, String>create(config)
                .commitInterval(Duration.ofSeconds(1))
                .commitBatchSize(50)
                .addAssignListener(p -> log.info("Particiones asignadas: {}", p))
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
