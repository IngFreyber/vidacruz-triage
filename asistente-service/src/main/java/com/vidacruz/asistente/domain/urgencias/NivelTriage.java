package com.vidacruz.asistente.domain.urgencias;

/**
 * Niveles de triaje tal como llegan en los eventos de {@code triaje.eventos}. Copia deliberada del contrato
 * (sin librería compartida con triage-service, ver ADR-0001).
 */
public enum NivelTriage {
    CRITICO,
    URGENTE,
    URGENTE_2,
    NO_URGENTE,
    NO_URGENTE_2
}
