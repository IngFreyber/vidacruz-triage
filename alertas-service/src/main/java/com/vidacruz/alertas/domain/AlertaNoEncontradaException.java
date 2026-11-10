package com.vidacruz.alertas.domain;

import java.util.UUID;

/**
 * La alerta solicitada no existe (HTTP 404).
 */
public class AlertaNoEncontradaException extends RuntimeException {

    public AlertaNoEncontradaException(UUID id) {
        super("No existe una alerta con id " + id);
    }
}
