package com.vidacruz.triage.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuración del monitor de SLA (RF-03).
 *
 * @param habilitado si el monitor corre (se apaga en tests que invocan el caso de uso directamente)
 * @param intervalo  cada cuánto se evalúan los pacientes activos
 * @param tamanoLote máximo de pacientes escalados por transacción
 */
@ConfigurationProperties(prefix = "triage.escalamiento")
public record EscalamientoProperties(
        @DefaultValue("true") boolean habilitado,
        @DefaultValue("30s") Duration intervalo,
        @DefaultValue("200") int tamanoLote) {
}
