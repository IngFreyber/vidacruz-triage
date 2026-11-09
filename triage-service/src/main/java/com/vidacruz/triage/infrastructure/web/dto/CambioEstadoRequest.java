package com.vidacruz.triage.infrastructure.web.dto;

import com.vidacruz.triage.domain.exception.DatosPacienteInvalidosException;
import com.vidacruz.triage.domain.exception.DatosPacienteInvalidosException.Violacion;
import com.vidacruz.triage.domain.model.EstadoPaciente;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Cuerpo de {@code PATCH /triage/pacientes/{id}/estado}. El estado llega como texto para poder responder un
 * 400 explícito ante un valor desconocido (en lugar de un error genérico de deserialización).
 */
public record CambioEstadoRequest(String estado) {

    public EstadoPaciente estadoDestino() {
        if (estado == null || estado.isBlank()) {
            throw invalido("es obligatorio");
        }
        try {
            return EstadoPaciente.valueOf(estado.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalido("valor '%s' no reconocido; valores permitidos: %s"
                    .formatted(estado, Arrays.toString(EstadoPaciente.values())));
        }
    }

    private static DatosPacienteInvalidosException invalido(String mensaje) {
        return new DatosPacienteInvalidosException(List.of(new Violacion("estado", mensaje)));
    }
}
