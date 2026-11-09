package com.vidacruz.asistente.infrastructure.web.dto;

import com.vidacruz.asistente.domain.consulta.EstadoRespuesta;
import com.vidacruz.asistente.domain.consulta.RespuestaClinica;

import java.util.List;

/**
 * Respuesta de {@code POST /asistente/consulta}: {@code respuesta} y {@code fuentes} según el enunciado, más
 * {@code estado} para que el consumidor distinga sin parsear texto si hubo respuesta clínica.
 */
public record ConsultaResponse(String respuesta, List<FuenteResponse> fuentes, EstadoRespuesta estado) {

    public record FuenteResponse(String protocolo, String seccion) {
    }

    public static ConsultaResponse desde(RespuestaClinica r) {
        return new ConsultaResponse(r.respuesta(),
                r.fuentes().stream().map(f -> new FuenteResponse(f.protocolo(), f.seccion())).toList(),
                r.estado());
    }
}
