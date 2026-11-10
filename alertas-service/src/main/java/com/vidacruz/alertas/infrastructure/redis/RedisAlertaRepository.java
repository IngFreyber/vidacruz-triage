package com.vidacruz.alertas.infrastructure.redis;

import com.vidacruz.alertas.application.port.out.AlertaRepository;
import com.vidacruz.alertas.domain.Alerta;
import com.vidacruz.alertas.domain.EstadoAlerta;
import com.vidacruz.alertas.infrastructure.config.AlertasProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Historial de alertas en Redis: un hash por alerta (con TTL de retención) y dos índices ordenados por fecha
 * de escalamiento (todas / pendientes). Creación y atención se hacen con scripts Lua atómicos.
 */
@Repository
public class RedisAlertaRepository implements AlertaRepository {

    static final String PREFIJO = "alertas:";
    static final String INDICE = PREFIJO + "indice";
    static final String PENDIENTES = PREFIJO + "pendientes";

    private static final RedisScript<Long> CREAR =
            RedisScript.of(new ClassPathResource("redis/crear_alerta.lua"), Long.class);
    private static final RedisScript<Long> ATENDER =
            RedisScript.of(new ClassPathResource("redis/atender_alerta.lua"), Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final AlertasProperties props;
    private final Clock clock;

    public RedisAlertaRepository(ReactiveStringRedisTemplate redis, AlertasProperties props, Clock clock) {
        this.redis = redis;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public Mono<Boolean> guardarSiNueva(Alerta a) {
        long corte = Instant.now(clock).minus(props.retencion()).toEpochMilli();
        List<String> args = new ArrayList<>(List.of(
                a.id().toString(),
                String.valueOf(a.escaladoEn().toEpochMilli()),
                String.valueOf(props.retencion().toSeconds()),
                String.valueOf(corte)));
        aCampos(a).forEach((campo, valor) -> {
            args.add(campo);
            args.add(valor);
        });
        return redis.execute(CREAR, List.of(clave(a.id()), INDICE, PENDIENTES), args)
                .next()
                .map(r -> r == 1L);
    }

    @Override
    public Mono<Alerta> buscar(UUID id) {
        return redis.<String, String>opsForHash().entries(clave(id))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .filter(m -> !m.isEmpty())
                .map(RedisAlertaRepository::desdeCampos);
    }

    @Override
    public Flux<Alerta> listar(EstadoAlerta estado, int limite) {
        String indice = estado == EstadoAlerta.PENDIENTE ? PENDIENTES : INDICE;
        return redis.opsForZSet().reverseRange(indice, Range.closed(0L, (long) limite * 2))
                .concatMap(id -> buscar(UUID.fromString(id)))
                .filter(a -> estado == null || a.estado() == estado)
                .take(limite);
    }

    @Override
    public Mono<Atencion> marcarAtendida(UUID id, String atendidaPor, Instant cuando) {
        return redis.execute(ATENDER, List.of(clave(id), PENDIENTES),
                        List.of(id.toString(), atendidaPor, cuando.toString()))
                .next()
                .flatMap(r -> r == -1L ? Mono.empty() : buscar(id).map(a -> new Atencion(a, r == 1L)));
    }

    static String clave(UUID id) {
        return PREFIJO + "alerta:" + id;
    }

    static Map<String, String> aCampos(Alerta a) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", a.id().toString());
        m.put("pacienteId", a.pacienteId().toString());
        m.put("nombrePaciente", a.nombrePaciente());
        m.put("quejaPrincipal", a.quejaPrincipal());
        m.put("nivel", a.nivel());
        m.put("slaMinutos", String.valueOf(a.slaMinutos()));
        m.put("minutosEspera", String.valueOf(a.minutosEspera()));
        m.put("fechaRegistro", a.fechaRegistro().toString());
        m.put("escaladoEn", a.escaladoEn().toString());
        m.put("mensaje", a.mensaje());
        m.put("estado", a.estado().name());
        return m;
    }

    static Alerta desdeCampos(Map<String, String> m) {
        String atendidaEn = m.get("atendidaEn");
        return new Alerta(
                UUID.fromString(m.get("id")),
                UUID.fromString(m.get("pacienteId")),
                m.get("nombrePaciente"),
                m.getOrDefault("quejaPrincipal", ""),
                m.get("nivel"),
                Long.parseLong(m.get("slaMinutos")),
                Long.parseLong(m.get("minutosEspera")),
                Instant.parse(m.get("fechaRegistro")),
                Instant.parse(m.get("escaladoEn")),
                m.get("mensaje"),
                EstadoAlerta.valueOf(m.get("estado")),
                m.get("atendidaPor"),
                atendidaEn == null ? null : Instant.parse(atendidaEn));
    }
}
