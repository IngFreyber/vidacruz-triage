package com.vidacruz.triage.infrastructure.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrato JSON publicado en el topic {@code triaje.eventos} (versión 1).
 * La key del mensaje Kafka es {@code pacienteId}: garantiza el orden de los eventos de un mismo paciente.
 *
 * @param eventId          id único del evento (los consumidores deduplican por él)
 * @param eventType        paciente_registrado | paciente_en_consulta | paciente_alta | paciente_escalado
 * @param eventVersion     versión del esquema del mensaje
 * @param occurredAt       instante del hecho (ISO 8601, UTC)
 * @param pacienteId       id del paciente
 * @param nombreCompleto   nombre del paciente (necesario para la alerta al equipo médico, RF-06)
 * @param quejaPrincipal   motivo de consulta (contexto clínico de la alerta)
 * @param nivel            nivel de triaje
 * @param estadoAnterior   estado previo (null en el registro)
 * @param estadoNuevo      estado resultante
 * @param escalado         flag de escalamiento tras el evento
 * @param slaMinutos       SLA del nivel
 * @param fechaRegistro    instante de registro del paciente
 * @param detalle          descripción legible del cambio
 */
public record EventoTriajeMensaje(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        UUID pacienteId,
        String nombreCompleto,
        String quejaPrincipal,
        String nivel,
        String estadoAnterior,
        String estadoNuevo,
        boolean escalado,
        long slaMinutos,
        Instant fechaRegistro,
        String detalle) {

    public static final int VERSION_ACTUAL = 1;
}
