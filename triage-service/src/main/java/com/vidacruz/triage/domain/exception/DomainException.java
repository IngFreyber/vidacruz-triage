package com.vidacruz.triage.domain.exception;

/**
 * Base de las excepciones de negocio. La capa web las traduce a códigos HTTP.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
