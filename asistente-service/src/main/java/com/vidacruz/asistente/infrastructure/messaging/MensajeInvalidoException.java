package com.vidacruz.asistente.infrastructure.messaging;

/**
 * Mensaje que no cumple el contrato (JSON inválido, campos obligatorios ausentes o valores desconocidos).
 * Reintentarlo no lo arreglaría: se registra y se descarta para no bloquear la partición (poison message).
 */
public class MensajeInvalidoException extends RuntimeException {

    public MensajeInvalidoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
