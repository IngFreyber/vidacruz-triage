package com.vidacruz.asistente.domain.consulta;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Regla RN-05 (anti-alucinación), última barrera antes de entregar una respuesta:
 * <ul>
 *   <li>Si el modelo declara no tener información, o no cita nada → {@code SIN_PROTOCOLO} (solo el aviso).</li>
 *   <li>Si alguna cita no corresponde a un fragmento <b>entregado</b> al modelo → la respuesta completa es
 *       inválida → {@code RESPUESTA_DESCARTADA} (no se devuelve nada del texto generado).</li>
 *   <li>Solo si todas las citas son válidas → {@code RESPONDIDA}, con las fuentes en su forma canónica.</li>
 * </ul>
 * La comparación tolera diferencias de mayúsculas, tildes y espacios, pero no inventa equivalencias.
 */
public final class ValidadorCitas {

    public RespuestaClinica validar(RespuestaGenerada generada, List<Fragmento> entregados) {
        if (generada == null || generada.sinInformacion()) {
            return RespuestaClinica.sinProtocolo();
        }
        boolean sinTexto = generada.respuesta() == null || generada.respuesta().isBlank();
        if (sinTexto && generada.fuentes().isEmpty()) {
            return RespuestaClinica.sinProtocolo();
        }
        if (sinTexto || generada.fuentes().isEmpty()) {
            // Contenido clínico sin citas (o citas sin contenido): no verificable.
            return RespuestaClinica.descartada();
        }

        Map<String, Fuente> canonicas = entregados.stream()
                .map(Fragmento::fuente)
                .collect(Collectors.toMap(ValidadorCitas::clave, Function.identity(), (a, b) -> a));

        Set<Fuente> validas = new LinkedHashSet<>();
        for (Fuente citada : generada.fuentes()) {
            Optional<Fuente> canonica = Optional.ofNullable(canonicas.get(clave(citada)));
            if (canonica.isEmpty()) {
                return RespuestaClinica.descartada();
            }
            validas.add(canonica.get());
        }
        return RespuestaClinica.respondida(generada.respuesta().strip(), List.copyOf(validas));
    }

    static String clave(Fuente f) {
        return normalizar(f.protocolo()) + "|" + normalizar(f.seccion());
    }

    private static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }
}
