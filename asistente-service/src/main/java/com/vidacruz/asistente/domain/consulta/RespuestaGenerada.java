package com.vidacruz.asistente.domain.consulta;

import java.util.List;

/**
 * Salida cruda del modelo de lenguaje, <b>no confiable</b> hasta pasar por {@link ValidadorCitas}.
 *
 * @param respuesta      texto generado
 * @param fuentes        citas declaradas por el modelo
 * @param sinInformacion el modelo declara que los fragmentos no responden la pregunta
 */
public record RespuestaGenerada(String respuesta, List<Fuente> fuentes, boolean sinInformacion) {

    public RespuestaGenerada {
        fuentes = fuentes == null ? List.of() : List.copyOf(fuentes);
    }
}
