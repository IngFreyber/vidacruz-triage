package com.vidacruz.alertas.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Alerta al equipo médico por un paciente que superó su SLA sin entrar a consulta (RF-06).
 *
 * <p>El {@code id} de la alerta es el {@code eventId} del escalamiento: una reentrega del mismo evento nunca
 * genera una segunda alerta (idempotencia por construcción).
 *
 * @param minutosEspera minutos entre el registro y el escalamiento (contexto para priorizar)
 * @param mensaje       texto listo para mostrar en cualquier canal
 */
public record Alerta(
        UUID id,
        UUID pacienteId,
        String nombrePaciente,
        String quejaPrincipal,
        String nivel,
        long slaMinutos,
        long minutosEspera,
        Instant fechaRegistro,
        Instant escaladoEn,
        String mensaje,
        EstadoAlerta estado,
        String atendidaPor,
        Instant atendidaEn) {

    public static Alerta desde(EscalamientoPaciente e) {
        long espera = Math.max(0, Duration.between(e.fechaRegistro(), e.escaladoEn()).toMinutes());
        String nombre = e.nombreCompleto() == null || e.nombreCompleto().isBlank()
                ? "Paciente " + e.pacienteId() : e.nombreCompleto();
        String queja = e.quejaPrincipal() == null ? "" : e.quejaPrincipal();
        String mensaje = "Paciente %s escalado: %s lleva %d min sin entrar a consulta (SLA %d min).%s"
                .formatted(e.nivel(), nombre, espera, e.slaMinutos(),
                        queja.isBlank() ? "" : " Motivo: " + queja + ".");
        return new Alerta(e.eventId(), e.pacienteId(), nombre, queja, e.nivel(), e.slaMinutos(), espera,
                e.fechaRegistro(), e.escaladoEn(), mensaje, EstadoAlerta.PENDIENTE, null, null);
    }

    public boolean pendiente() {
        return estado == EstadoAlerta.PENDIENTE;
    }
}
