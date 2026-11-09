package com.vidacruz.triage.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Hecho de dominio producido por cada cambio de estado o flag del paciente.
 * Es la fuente única para la entrada de auditoría (RN-04) y para el mensaje publicado en Kafka.
 *
 * @param eventoId       identificador único (idempotencia en consumidores)
 * @param tipo           tipo de evento
 * @param pacienteId     paciente afectado
 * @param nivel          nivel de triaje del paciente al momento del evento
 * @param estadoAnterior estado previo ({@code null} en el registro)
 * @param estadoNuevo    estado resultante
 * @param detalle        descripción legible del cambio (p. ej. cambio de flag)
 * @param ocurridoEn     timestamp del hecho
 */
public record EventoPaciente(
        UUID eventoId,
        TipoEvento tipo,
        UUID pacienteId,
        NivelTriage nivel,
        EstadoPaciente estadoAnterior,
        EstadoPaciente estadoNuevo,
        String detalle,
        Instant ocurridoEn) {
}
