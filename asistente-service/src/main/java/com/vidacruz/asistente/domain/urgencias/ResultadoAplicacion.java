package com.vidacruz.asistente.domain.urgencias;

/**
 * Resultado de aplicar un evento a la vista. Solo {@link #APLICADO} modifica los contadores.
 */
public enum ResultadoAplicacion {
    /** El evento actualizó la vista. */
    APLICADO,
    /** Ya se había procesado este {@code eventId} (reentrega at-least-once). */
    DUPLICADO,
    /** El paciente ya estaba de alta: ningún evento posterior lo reactiva. */
    PACIENTE_FINALIZADO,
    /** El evento es más antiguo que el último aplicado para el paciente. */
    OBSOLETO
}
