package com.vidacruz.triage.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.application.port.out.OutboxRepository;
import com.vidacruz.triage.domain.model.EventoPaciente;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.infrastructure.messaging.EventoTriajeMensaje;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Escribe el evento en {@code outbox_eventos} dentro de la transacción en curso.
 */
@Repository
public class R2dbcOutboxRepository implements OutboxRepository {

    private static final String INSERT = """
            INSERT INTO outbox_eventos (evento_id, agregado_id, tipo_evento, payload, creado_en)
            VALUES (:eventoId, :agregadoId, :tipo, :payload, :creadoEn)
            """;

    /**
     * Toma un lote de pendientes en orden de inserción y los bloquea. {@code SKIP LOCKED} permite que varias
     * réplicas publiquen en paralelo sin tomar los mismos eventos ni bloquearse entre sí.
     */
    private static final String SELECT_PENDIENTES = """
            SELECT id, evento_id, agregado_id, tipo_evento, payload::text AS payload
              FROM outbox_eventos
             WHERE publicado_en IS NULL
             ORDER BY id
             LIMIT :lote
               FOR UPDATE SKIP LOCKED
            """;

    private static final String MARCAR_PUBLICADOS = """
            UPDATE outbox_eventos SET publicado_en = :ahora WHERE id = ANY(:ids)
            """;

    private static final String SUMAR_INTENTO = """
            UPDATE outbox_eventos SET intentos = intentos + 1 WHERE id = ANY(:ids)
            """;

    private final DatabaseClient db;
    private final ObjectMapper mapper;

    public R2dbcOutboxRepository(DatabaseClient db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    /**
     * Evento pendiente de publicar.
     */
    public record EventoPendiente(long id, UUID eventoId, UUID agregadoId, String tipoEvento, String payload) {
    }

    /** Debe invocarse dentro de una transacción para que el bloqueo se mantenga hasta marcar publicados. */
    public Flux<EventoPendiente> bloquearPendientes(int lote) {
        return db.sql(SELECT_PENDIENTES)
                .bind("lote", lote)
                .map(r -> new EventoPendiente(
                        r.get("id", Long.class),
                        r.get("evento_id", UUID.class),
                        r.get("agregado_id", UUID.class),
                        r.get("tipo_evento", String.class),
                        r.get("payload", String.class)))
                .all();
    }

    public Mono<Long> marcarPublicados(List<Long> ids, Instant ahora) {
        if (ids.isEmpty()) {
            return Mono.just(0L);
        }
        return db.sql(MARCAR_PUBLICADOS)
                .bind("ahora", ahora)
                .bind("ids", ids.toArray(Long[]::new))
                .fetch().rowsUpdated();
    }

    public Mono<Long> sumarIntento(List<Long> ids) {
        if (ids.isEmpty()) {
            return Mono.just(0L);
        }
        return db.sql(SUMAR_INTENTO)
                .bind("ids", ids.toArray(Long[]::new))
                .fetch().rowsUpdated();
    }

    @Override
    public Mono<Void> guardar(EventoPaciente evento, Paciente paciente) {
        return Mono.fromCallable(() -> serializar(evento, paciente))
                .flatMap(json -> db.sql(INSERT)
                        .bind("eventoId", evento.eventoId())
                        .bind("agregadoId", evento.pacienteId())
                        .bind("tipo", evento.tipo().codigo())
                        .bind("payload", Json.of(json))
                        .bind("creadoEn", evento.ocurridoEn())
                        .then());
    }

    private String serializar(EventoPaciente e, Paciente p) throws JsonProcessingException {
        return mapper.writeValueAsString(new EventoTriajeMensaje(
                e.eventoId(),
                e.tipo().codigo(),
                EventoTriajeMensaje.VERSION_ACTUAL,
                e.ocurridoEn(),
                e.pacienteId(),
                p.nombreCompleto(),
                p.quejaPrincipal(),
                e.nivel().name(),
                e.estadoAnterior() == null ? null : e.estadoAnterior().name(),
                e.estadoNuevo().name(),
                p.escalado(),
                p.slaMinutos(),
                p.fechaRegistro(),
                e.detalle()));
    }
}
