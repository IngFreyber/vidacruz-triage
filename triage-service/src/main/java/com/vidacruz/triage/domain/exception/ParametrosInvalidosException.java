package com.vidacruz.triage.domain.exception;

import com.vidacruz.triage.domain.exception.DatosPacienteInvalidosException.Violacion;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Parámetros de consulta inválidos (p. ej. el rango de fechas del reporte) → HTTP 400.
 */
public class ParametrosInvalidosException extends DomainException {

    private final transient List<Violacion> violaciones;

    public ParametrosInvalidosException(List<Violacion> violaciones) {
        super(violaciones.stream()
                .map(v -> v.campo() + ": " + v.mensaje())
                .collect(Collectors.joining("; ", "Parámetros inválidos - ", "")));
        this.violaciones = List.copyOf(violaciones);
    }

    public ParametrosInvalidosException(String campo, String mensaje) {
        this(List.of(new Violacion(campo, mensaje)));
    }

    public List<Violacion> violaciones() {
        return violaciones;
    }
}
