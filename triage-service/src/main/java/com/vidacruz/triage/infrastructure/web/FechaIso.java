package com.vidacruz.triage.infrastructure.web;

import com.vidacruz.triage.domain.exception.ParametrosInvalidosException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Interpreta fechas ISO 8601 de parámetros de consulta. Formatos aceptados:
 * <ul>
 *   <li>Con zona: {@code 2026-11-09T10:00:00Z}, {@code 2026-11-09T05:00:00-05:00}</li>
 *   <li>Sin zona (se asume UTC): {@code 2026-11-09T10:00:00}</li>
 *   <li>Solo fecha (inicio del día en UTC): {@code 2026-11-09}</li>
 * </ul>
 * Un {@code +} sin codificar en la URL llega como espacio; se restituye para no rechazar
 * {@code 2026-11-09T10:00:00+01:00} escrito a mano.
 */
final class FechaIso {

    private FechaIso() {
    }

    static Instant parsear(String campo, String valor) {
        if (valor == null || valor.isBlank()) {
            throw new ParametrosInvalidosException(campo, "es obligatorio (fecha ISO 8601, p. ej. 2026-11-09T10:00:00Z)");
        }
        String texto = valor.strip().replace(' ', '+');
        try {
            return OffsetDateTime.parse(texto).toInstant();
        } catch (DateTimeParseException ignorada) {
            // se intentan los formatos sin zona
        }
        try {
            return LocalDateTime.parse(texto).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignorada) {
            // se intenta solo fecha
        }
        try {
            return LocalDate.parse(texto).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            throw new ParametrosInvalidosException(campo,
                    "'%s' no es una fecha ISO 8601 válida (p. ej. 2026-11-09T10:00:00Z o 2026-11-09)".formatted(valor));
        }
    }
}
