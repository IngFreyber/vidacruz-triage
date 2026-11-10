package com.vidacruz.asistente.infrastructure.redis;

import com.vidacruz.asistente.application.port.out.VistaUrgenciasRepository;
import com.vidacruz.asistente.domain.urgencias.EstadoUrgencias;
import com.vidacruz.asistente.domain.urgencias.EventoTriaje;
import com.vidacruz.asistente.domain.urgencias.NivelTriage;
import com.vidacruz.asistente.domain.urgencias.ResultadoAplicacion;
import com.vidacruz.asistente.infrastructure.config.VistaUrgenciasProperties;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Vista de urgencias en Redis. Cada evento se aplica con un script Lua atómico (ver
 * {@code redis/aplicar_evento.lua}): deduplicación, guard de estado terminal, descarte de eventos obsoletos y
 * ajuste de contadores ocurren como una sola operación, sin carreras entre consumidores.
 *
 * <p>Nota: las claves no comparten hash slot, por lo que se requiere Redis sin modo cluster (Azure Cache for
 * Redis Basic/Standard) — documentado en ADR-0004.
 */
@Repository
public class RedisVistaUrgenciasRepository implements VistaUrgenciasRepository {

    static final String PREFIJO = "urgencias:";
    static final String CLAVE_ACTIVOS = PREFIJO + "activos";
    static final String CLAVE_ACTUALIZADO = PREFIJO + "actualizado-en";

    private static final RedisScript<Long> SCRIPT =
            ScriptsLua.cargar("redis/aplicar_evento.lua");

    private final ReactiveStringRedisTemplate redis;
    private final VistaUrgenciasProperties props;

    public RedisVistaUrgenciasRepository(ReactiveStringRedisTemplate redis, VistaUrgenciasProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public Mono<ResultadoAplicacion> aplicar(EventoTriaje e) {
        List<String> claves = List.of(
                PREFIJO + "evento:" + e.eventId(),
                PREFIJO + "paciente:" + e.pacienteId(),
                CLAVE_ACTIVOS,
                CLAVE_ACTUALIZADO);
        List<String> argumentos = List.of(
                String.valueOf(props.ttlDeduplicacion().toSeconds()),
                e.nivel().name(),
                e.estadoNuevo(),
                String.valueOf(e.ocurridoEn().toEpochMilli()),
                String.valueOf(props.ttlPacienteAlta().toSeconds()));
        return redis.execute(SCRIPT, claves, argumentos)
                .next()
                .map(RedisVistaUrgenciasRepository::resultado);
    }

    @Override
    public Mono<EstadoUrgencias> consultar() {
        Mono<Map<NivelTriage, Long>> conteos = redis.<String, String>opsForHash().entries(CLAVE_ACTIVOS)
                .collectMap(entrada -> NivelTriage.valueOf(entrada.getKey()),
                        entrada -> Long.parseLong(entrada.getValue()),
                        () -> new EnumMap<>(NivelTriage.class));
        Mono<Instant> actualizado = redis.opsForValue().get(CLAVE_ACTUALIZADO)
                .map(ms -> Instant.ofEpochMilli(Long.parseLong(ms)));
        return conteos.zipWith(actualizado.map(Optional::of).defaultIfEmpty(Optional.empty()))
                .map(t -> new EstadoUrgencias(t.getT1(), t.getT2().orElse(null)));
    }

    private static ResultadoAplicacion resultado(Long codigo) {
        return switch (codigo.intValue()) {
            case 1 -> ResultadoAplicacion.APLICADO;
            case 0 -> ResultadoAplicacion.DUPLICADO;
            case 2 -> ResultadoAplicacion.PACIENTE_FINALIZADO;
            case 3 -> ResultadoAplicacion.OBSOLETO;
            default -> throw new IllegalStateException("Código inesperado del script de vista: " + codigo);
        };
    }
}
