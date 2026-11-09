package com.vidacruz.triage.application.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Hora actual truncada a milisegundos: PostgreSQL guarda microsegundos y algunos relojes del SO entregan
 * nanosegundos; truncar evita diferencias entre el valor devuelto por la API y el leído de la base.
 */
public final class Tiempo {

    private Tiempo() {
    }

    public static Instant ahora(Clock clock) {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }
}
