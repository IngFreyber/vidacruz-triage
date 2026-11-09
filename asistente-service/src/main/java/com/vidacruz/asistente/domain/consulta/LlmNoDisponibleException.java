package com.vidacruz.asistente.domain.consulta;

/**
 * El proveedor de LLM no está disponible (error, timeout o circuito abierto). Modo degradado → HTTP 503.
 */
public class LlmNoDisponibleException extends RuntimeException {

    public LlmNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
