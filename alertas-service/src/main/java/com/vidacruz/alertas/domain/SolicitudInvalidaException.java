package com.vidacruz.alertas.domain;

/**
 * Datos de entrada inválidos (HTTP 400).
 */
public class SolicitudInvalidaException extends RuntimeException {

    private final String campo;

    public SolicitudInvalidaException(String campo, String mensaje) {
        super(mensaje);
        this.campo = campo;
    }

    public String campo() {
        return campo;
    }
}
