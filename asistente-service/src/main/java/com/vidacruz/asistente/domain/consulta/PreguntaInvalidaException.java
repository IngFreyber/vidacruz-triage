package com.vidacruz.asistente.domain.consulta;

/**
 * La pregunta no cumple las reglas de entrada (HTTP 400).
 */
public class PreguntaInvalidaException extends RuntimeException {

    public PreguntaInvalidaException(String mensaje) {
        super(mensaje);
    }
}
