package com.vidacruz.triage.infrastructure.web.dto;

import com.vidacruz.triage.domain.model.EstadoPaciente;
import com.vidacruz.triage.domain.model.NivelTriage;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.SignosVitales;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Respuesta de {@code GET /triage/pacientes/{id}}: datos completos, estado actual, flags y tiempo en el
 * estado actual.
 */
public record PacienteResponse(
        UUID id,
        String nombreCompleto,
        int edad,
        SignosVitales signosVitales,
        String quejaPrincipal,
        NivelTriage nivel,
        List<String> criteriosClasificacion,
        long slaMinutos,
        Instant vencimientoSla,
        EstadoPaciente estado,
        Instant fechaRegistro,
        Instant fechaCambioEstado,
        TiempoEnEstado tiempoEnEstadoActual,
        Flags flags) {

    /**
     * @param escalado           el paciente superó su SLA sin entrar a consulta (flag persistente, RF-03)
     * @param fechaEscalamiento  cuándo fue escalado
     * @param slaVencido         el paciente sigue esperando y su SLA ya venció
     */
    public record Flags(boolean escalado, Instant fechaEscalamiento, boolean slaVencido) {
    }

    /**
     * @param segundos duración en segundos
     * @param minutos  duración en minutos completos
     * @param iso8601  duración en formato ISO 8601 (p. ej. PT12M30S)
     */
    public record TiempoEnEstado(long segundos, long minutos, String iso8601) {

        static TiempoEnEstado de(Duration d) {
            Duration positiva = d.isNegative() ? Duration.ZERO : d;
            return new TiempoEnEstado(positiva.toSeconds(), positiva.toMinutes(), positiva.toString());
        }
    }

    public static PacienteResponse desde(Paciente p, Instant ahora) {
        boolean slaVencido = p.estado() == EstadoPaciente.REGISTRADO && p.slaVencido(ahora);
        return new PacienteResponse(p.id(), p.nombreCompleto(), p.edad(), p.signosVitales(), p.quejaPrincipal(),
                p.nivel(), p.criteriosClasificacion(), p.sla().toMinutes(), p.vencimientoSla(), p.estado(),
                p.fechaRegistro(), p.fechaCambioEstado(), TiempoEnEstado.de(p.tiempoEnEstadoActual(ahora)),
                new Flags(p.escalado(), p.fechaEscalamiento(), slaVencido));
    }
}
