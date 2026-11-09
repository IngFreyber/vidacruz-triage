package com.vidacruz.triage.infrastructure.persistence;

import com.vidacruz.triage.application.port.out.PacienteRepository;
import com.vidacruz.triage.domain.exception.ConflictoConcurrenciaException;
import com.vidacruz.triage.domain.model.EstadoPaciente;
import com.vidacruz.triage.domain.model.NivelTriage;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.SignosVitales;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador R2DBC del agregado Paciente. Se usa SQL explícito con {@link DatabaseClient} para tener control
 * total sobre las actualizaciones condicionales (concurrencia optimista e idempotencia del escalamiento).
 */
@Repository
public class R2dbcPacienteRepository implements PacienteRepository {

    private static final String INSERT = """
            INSERT INTO pacientes (id, nombre_completo, edad, frecuencia_cardiaca, presion_sistolica,
                presion_diastolica, spo2, temperatura, dolor, gcs, queja_principal, nivel, criterios_clasificacion,
                sla_segundos, estado, escalado, fecha_registro, fecha_cambio_estado, fecha_escalamiento, version)
            VALUES (:id, :nombre, :edad, :fc, :sist, :dias, :spo2, :temp, :dolor, :gcs, :queja, :nivel, :criterios,
                :sla, :estado, :escalado, :fechaRegistro, :fechaCambioEstado, NULL, 0)
            """;

    private static final String SELECT_POR_ID = "SELECT * FROM pacientes WHERE id = :id";

    private static final String UPDATE_OPTIMISTA = """
            UPDATE pacientes
               SET estado = :estado, escalado = :escalado, fecha_cambio_estado = :fechaCambioEstado,
                   fecha_escalamiento = :fechaEscalamiento, version = version + 1
             WHERE id = :id AND version = :version
            """;

    /**
     * Candidatos a escalamiento (RF-03). El SLA vigente al registrar queda fijado por paciente
     * ({@code sla_segundos}): cambiar la configuración no altera retroactivamente a quienes ya esperan.
     */
    private static final String SELECT_SLA_VENCIDO = """
            SELECT * FROM pacientes
             WHERE estado = 'REGISTRADO'
               AND escalado = FALSE
               AND fecha_registro + make_interval(secs => sla_segundos) < :ahora
             ORDER BY fecha_registro
             LIMIT :limite
               FOR UPDATE SKIP LOCKED
            """;

    private final DatabaseClient db;

    public R2dbcPacienteRepository(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Void> insertar(Paciente p) {
        SignosVitales s = p.signosVitales();
        return db.sql(INSERT)
                .bind("id", p.id())
                .bind("nombre", p.nombreCompleto())
                .bind("edad", p.edad())
                .bind("fc", s.frecuenciaCardiaca())
                .bind("sist", s.presionSistolica())
                .bind("dias", s.presionDiastolica())
                .bind("spo2", s.spo2())
                .bind("temp", BigDecimal.valueOf(s.temperatura()))
                .bind("dolor", s.dolor())
                .bind("gcs", s.gcs())
                .bind("queja", p.quejaPrincipal())
                .bind("nivel", p.nivel().name())
                .bind("criterios", p.criteriosClasificacion().toArray(String[]::new))
                .bind("sla", Math.toIntExact(p.sla().toSeconds()))
                .bind("estado", p.estado().name())
                .bind("escalado", p.escalado())
                .bind("fechaRegistro", p.fechaRegistro())
                .bind("fechaCambioEstado", p.fechaCambioEstado())
                .then();
    }

    @Override
    public Mono<Paciente> buscarPorId(UUID id) {
        return db.sql(SELECT_POR_ID)
                .bind("id", id)
                .map(R2dbcPacienteRepository::mapear)
                .one();
    }

    @Override
    public Mono<Void> actualizar(Paciente p) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(UPDATE_OPTIMISTA)
                .bind("id", p.id())
                .bind("version", p.version())
                .bind("estado", p.estado().name())
                .bind("escalado", p.escalado())
                .bind("fechaCambioEstado", p.fechaCambioEstado());
        spec = p.fechaEscalamiento() == null
                ? spec.bindNull("fechaEscalamiento", Instant.class)
                : spec.bind("fechaEscalamiento", p.fechaEscalamiento());
        return spec.fetch().rowsUpdated()
                .flatMap(filas -> filas == 1 ? Mono.<Void>empty()
                        : Mono.error(new ConflictoConcurrenciaException(p.id())));
    }

    @Override
    public Flux<Paciente> bloquearConSlaVencido(Instant ahora, int limite) {
        return db.sql(SELECT_SLA_VENCIDO)
                .bind("ahora", ahora)
                .bind("limite", limite)
                .map(R2dbcPacienteRepository::mapear)
                .all();
    }

    static Paciente mapear(Readable row) {
        String[] criterios = row.get("criterios_clasificacion", String[].class);
        List<String> listaCriterios = criterios == null ? List.of() : Arrays.asList(criterios);
        return new Paciente(
                row.get("id", UUID.class),
                row.get("nombre_completo", String.class),
                row.get("edad", Integer.class),
                new SignosVitales(
                        row.get("frecuencia_cardiaca", Integer.class),
                        row.get("presion_sistolica", Integer.class),
                        row.get("presion_diastolica", Integer.class),
                        row.get("spo2", Integer.class),
                        row.get("temperatura", BigDecimal.class).doubleValue(),
                        row.get("dolor", Integer.class),
                        row.get("gcs", Integer.class)),
                row.get("queja_principal", String.class),
                NivelTriage.valueOf(row.get("nivel", String.class)),
                listaCriterios,
                Duration.ofSeconds(row.get("sla_segundos", Integer.class)),
                EstadoPaciente.valueOf(row.get("estado", String.class)),
                Boolean.TRUE.equals(row.get("escalado", Boolean.class)),
                row.get("fecha_registro", Instant.class),
                row.get("fecha_cambio_estado", Instant.class),
                row.get("fecha_escalamiento", Instant.class),
                row.get("version", Long.class));
    }
}
