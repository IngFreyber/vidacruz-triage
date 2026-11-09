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
import reactor.core.publisher.Mono;

/**
 * Escribe el evento en {@code outbox_eventos} dentro de la transacción en curso.
 */
@Repository
public class R2dbcOutboxRepository implements OutboxRepository {

    private static final String INSERT = """
            INSERT INTO outbox_eventos (evento_id, agregado_id, tipo_evento, payload, creado_en)
            VALUES (:eventoId, :agregadoId, :tipo, :payload, :creadoEn)
            """;

    private final DatabaseClient db;
    private final ObjectMapper mapper;

    public R2dbcOutboxRepository(DatabaseClient db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
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
                p.sla().toMinutes(),
                p.fechaRegistro(),
                e.detalle()));
    }
}
