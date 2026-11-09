package com.vidacruz.triage.domain.exception;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Uno o más datos del paciente están fuera de rango o ausentes (RF-01 → HTTP 400).
 */
public class DatosPacienteInvalidosException extends DomainException {

    private final transient List<Violacion> violaciones;

    public DatosPacienteInvalidosException(List<Violacion> violaciones) {
        super(violaciones.stream()
                .map(v -> v.campo() + ": " + v.mensaje())
                .collect(Collectors.joining("; ", "Datos del paciente inválidos - ", "")));
        this.violaciones = List.copyOf(violaciones);
    }

    public List<Violacion> violaciones() {
        return violaciones;
    }

    /**
     * @param campo   nombre del campo tal como se expone en la API
     * @param mensaje descripción de la regla incumplida
     */
    public record Violacion(String campo, String mensaje) {
    }
}
