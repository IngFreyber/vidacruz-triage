package com.vidacruz.triage.domain.model;

/**
 * Tipos de evento clínico. El {@code codigo} es el nombre publicado en el topic {@code triaje.eventos}
 * y registrado en la auditoría (RN-04).
 */
public enum TipoEvento {
    PACIENTE_REGISTRADO("paciente_registrado"),
    PACIENTE_EN_CONSULTA("paciente_en_consulta"),
    PACIENTE_ALTA("paciente_alta"),
    PACIENTE_ESCALADO("paciente_escalado");

    private final String codigo;

    TipoEvento(String codigo) {
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }

    public static TipoEvento porTransicionA(EstadoPaciente destino) {
        return switch (destino) {
            case EN_CONSULTA -> PACIENTE_EN_CONSULTA;
            case ALTA -> PACIENTE_ALTA;
            case REGISTRADO -> PACIENTE_REGISTRADO;
        };
    }
}
