package com.vidacruz.asistente.domain.consulta;

/**
 * Sección de un protocolo oficial recuperada para responder una pregunta.
 *
 * @param protocolo  identificador del protocolo (p. ej. PROTO-001)
 * @param titulo     título del protocolo
 * @param seccion    nombre de la sección (p. ej. "Medidas iniciales")
 * @param contenido  texto literal de la sección
 * @param similitud  similitud semántica con la pregunta (0..1)
 */
public record Fragmento(String protocolo, String titulo, String seccion, String contenido, double similitud) {

    public Fuente fuente() {
        return new Fuente(protocolo, seccion);
    }
}
