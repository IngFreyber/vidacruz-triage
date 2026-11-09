package com.vidacruz.triage.infrastructure.persistence;

import com.vidacruz.triage.application.port.out.AuditoriaRepository;
import com.vidacruz.triage.domain.model.EntradaAuditoria;
import com.vidacruz.triage.domain.model.EstadoPaciente;
import com.vidacruz.triage.domain.model.EventoPaciente;
import com.vidacruz.triage.domain.model.NivelTriage;
import com.vidacruz.triage.domain.model.TipoEvento;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * Auditoría clínica append-only (RN-04). La tabla además tiene un trigger que rechaza UPDATE/DELETE/TRUNCATE.
 */
@Repository
public class R2dbcAuditoriaRepository implements AuditoriaRepository {

    private static final String INSERT = """
            INSERT INTO auditoria_clinica (evento_id, paciente_id, tipo_evento, estado_anterior, estado_nuevo,
                nivel, detalle, ocurrido_en)
            VALUES (:eventoId, :pacienteId, :tipo, :anterior, :nuevo, :nivel, :detalle, :ocurridoEn)
            """;

    private static final String SELECT_POR_PACIENTE = """
            SELECT evento_id, paciente_id, tipo_evento, estado_anterior, estado_nuevo, nivel, detalle, ocurrido_en
              FROM auditoria_clinica
             WHERE paciente_id = :pacienteId
             ORDER BY ocurrido_en, id
            """;

    private final DatabaseClient db;

    public R2dbcAuditoriaRepository(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Void> registrar(EventoPaciente e) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(INSERT)
                .bind("eventoId", e.eventoId())
                .bind("pacienteId", e.pacienteId())
                .bind("tipo", e.tipo().codigo())
                .bind("nuevo", e.estadoNuevo().name())
                .bind("nivel", e.nivel().name())
                .bind("detalle", e.detalle())
                .bind("ocurridoEn", e.ocurridoEn());
        spec = e.estadoAnterior() == null
                ? spec.bindNull("anterior", String.class)
                : spec.bind("anterior", e.estadoAnterior().name());
        return spec.then();
    }

    @Override
    public Flux<EntradaAuditoria> buscarPorPaciente(UUID pacienteId) {
        return db.sql(SELECT_POR_PACIENTE)
                .bind("pacienteId", pacienteId)
                .map(row -> {
                    String anterior = row.get("estado_anterior", String.class);
                    return new EntradaAuditoria(
                            row.get("evento_id", UUID.class),
                            row.get("paciente_id", UUID.class),
                            tipoPorCodigo(row.get("tipo_evento", String.class)),
                            anterior == null ? null : EstadoPaciente.valueOf(anterior),
                            EstadoPaciente.valueOf(row.get("estado_nuevo", String.class)),
                            NivelTriage.valueOf(row.get("nivel", String.class)),
                            row.get("detalle", String.class),
                            row.get("ocurrido_en", Instant.class));
                })
                .all();
    }

    private static TipoEvento tipoPorCodigo(String codigo) {
        return Arrays.stream(TipoEvento.values())
                .filter(t -> t.codigo().equals(codigo))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Tipo de evento desconocido en auditoría: " + codigo));
    }
}
