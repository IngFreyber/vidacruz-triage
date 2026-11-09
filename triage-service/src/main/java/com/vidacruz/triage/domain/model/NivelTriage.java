package com.vidacruz.triage.domain.model;

/**
 * Niveles de triaje (RN-01). El número menor es el de mayor gravedad.
 */
public enum NivelTriage {
    CRITICO(1),
    URGENTE(2),
    URGENTE_2(3),
    NO_URGENTE(4),
    NO_URGENTE_2(5);

    private final int prioridad;

    NivelTriage(int prioridad) {
        this.prioridad = prioridad;
    }

    public int prioridad() {
        return prioridad;
    }

    public boolean esMasGraveQue(NivelTriage otro) {
        return this.prioridad < otro.prioridad;
    }
}
