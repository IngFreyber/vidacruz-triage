package com.vidacruz.triage.application.port.out;

import com.vidacruz.triage.domain.model.NivelTriage;

/**
 * Métricas de negocio del triaje (observabilidad).
 */
public interface MetricasTriage {

    /** Un paciente fue clasificado (RF-01): {@code triage.clasificacion{nivel}}. */
    void pacienteClasificado(NivelTriage nivel);

    /** Un paciente fue escalado por SLA vencido (RF-03): {@code triage.escalamientos{nivel}}. */
    void pacienteEscalado(NivelTriage nivel);
}
