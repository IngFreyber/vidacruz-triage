package com.vidacruz.asistente.domain.consulta;

/**
 * Resultado de una consulta clínica (RN-05).
 */
public enum EstadoRespuesta {
    /** Respuesta sustentada en al menos una fuente válida del corpus. */
    RESPONDIDA,
    /** No hay protocolo relevante: solo el aviso, sin contenido clínico generado. */
    SIN_PROTOCOLO,
    /** El modelo produjo una respuesta no verificable (cita inexistente, sin citas o formato inválido): se descarta. */
    RESPUESTA_DESCARTADA
}
