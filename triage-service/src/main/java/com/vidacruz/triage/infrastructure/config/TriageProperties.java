package com.vidacruz.triage.infrastructure.config;

import com.vidacruz.triage.domain.model.NivelTriage;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Propiedades de negocio configurables del servicio de triaje.
 *
 * @param sla                   SLA por nivel (RN-02). Configurable para probar el escalamiento rápidamente (RF-03)
 * @param quejasAdministrativas quejas que llevan del nivel 4 al 5 (RN-01)
 */
@ConfigurationProperties(prefix = "triage")
public record TriageProperties(
        Map<NivelTriage, Duration> sla,
        List<String> quejasAdministrativas) {
}
