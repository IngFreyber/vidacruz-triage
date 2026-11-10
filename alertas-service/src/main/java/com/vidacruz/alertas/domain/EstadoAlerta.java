package com.vidacruz.alertas.domain;

/**
 * Ciclo de vida de una alerta de escalamiento.
 */
public enum EstadoAlerta {
    /** Emitida, nadie del equipo médico la ha tomado. */
    PENDIENTE,
    /** Un profesional confirmó que la está atendiendo (acknowledge). */
    ATENDIDA
}
