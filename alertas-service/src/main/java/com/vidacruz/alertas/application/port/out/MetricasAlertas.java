package com.vidacruz.alertas.application.port.out;

/**
 * Métricas de negocio de las alertas (observabilidad).
 */
public interface MetricasAlertas {

    /** Alerta nueva emitida al equipo médico: {@code alertas.emitidas{nivel}}. */
    void alertaEmitida(String nivel);

    /** Resultado de entregar una alerta por un canal externo: {@code alertas.notificaciones{canal,resultado}}. */
    void notificacion(String canal, boolean exitosa);
}
