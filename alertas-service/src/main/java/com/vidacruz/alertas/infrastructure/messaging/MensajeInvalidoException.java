package com.vidacruz.alertas.infrastructure.messaging;

/**
 * Mensaje fuera de contrato: no se reintenta (poison message), se registra y se descarta.
 */
public class MensajeInvalidoException extends RuntimeException {

    public MensajeInvalidoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
