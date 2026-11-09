package com.vidacruz.asistente.domain.consulta;

import java.util.List;

/**
 * Respuesta entregada al médico. Solo un estado {@link EstadoRespuesta#RESPONDIDA} contiene contenido clínico,
 * y siempre con al menos una fuente válida.
 */
public record RespuestaClinica(EstadoRespuesta estado, String respuesta, List<Fuente> fuentes) {

    public static final String AVISO_SIN_PROTOCOLO =
            "No existe un protocolo disponible en el corpus oficial para esta pregunta.";
    public static final String AVISO_DESCARTADA =
            "No existe un protocolo disponible que permita responder esta pregunta con fuentes verificables. "
                    + "La respuesta generada se descartó por no estar sustentada en el corpus oficial.";

    public RespuestaClinica {
        fuentes = List.copyOf(fuentes);
        if (estado == EstadoRespuesta.RESPONDIDA && fuentes.isEmpty()) {
            throw new IllegalArgumentException("RN-05: una respuesta clínica requiere al menos una fuente");
        }
    }

    public static RespuestaClinica sinProtocolo() {
        return new RespuestaClinica(EstadoRespuesta.SIN_PROTOCOLO, AVISO_SIN_PROTOCOLO, List.of());
    }

    public static RespuestaClinica descartada() {
        return new RespuestaClinica(EstadoRespuesta.RESPUESTA_DESCARTADA, AVISO_DESCARTADA, List.of());
    }

    public static RespuestaClinica respondida(String respuesta, List<Fuente> fuentes) {
        return new RespuestaClinica(EstadoRespuesta.RESPONDIDA, respuesta, fuentes);
    }
}
