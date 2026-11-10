package com.vidacruz.alertas.infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.alertas.application.port.out.DifusorAlertas;
import com.vidacruz.alertas.domain.Alerta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Difusión de alertas a <b>todas las réplicas</b> mediante Redis Pub/Sub.
 *
 * <p>Problema que resuelve: el consumer group de Kafka reparte las particiones entre réplicas, así que cada
 * escalamiento lo procesa una sola instancia, pero el tablero del médico puede estar conectado (SSE) a
 * cualquier otra. La instancia que procesa publica en {@code alertas:eventos}; cada réplica escucha el canal y
 * lo entrega a sus suscriptores SSE locales.
 *
 * <p>Una sola suscripción a Redis por instancia, compartida entre todas las conexiones SSE ({@code share()}),
 * y reconexión automática con backoff si Redis se cae.
 */
@Component
public class RedisDifusorAlertas implements DifusorAlertas {

    private static final Logger log = LoggerFactory.getLogger(RedisDifusorAlertas.class);
    static final String CANAL = "alertas:eventos";

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Flux<Alerta> flujo;

    public RedisDifusorAlertas(ReactiveStringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
        this.flujo = redis.listenToChannel(CANAL)
                .flatMap(mensaje -> deserializar(mensaje.getMessage()))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(15))
                        .doBeforeRetry(s -> log.warn("Suscripción a {} perdida; reconectando: {}", CANAL,
                                s.failure().toString())))
                .share();
    }

    @Override
    public Mono<Void> difundir(Alerta alerta) {
        return Mono.fromCallable(() -> mapper.writeValueAsString(alerta))
                .flatMap(json -> redis.convertAndSend(CANAL, json))
                .then();
    }

    @Override
    public Flux<Alerta> suscribir() {
        return flujo;
    }

    private Mono<Alerta> deserializar(String json) {
        try {
            return Mono.just(mapper.readValue(json, Alerta.class));
        } catch (JsonProcessingException e) {
            log.error("Mensaje inválido en {}: {}", CANAL, e.getMessage());
            return Mono.empty();
        }
    }
}
