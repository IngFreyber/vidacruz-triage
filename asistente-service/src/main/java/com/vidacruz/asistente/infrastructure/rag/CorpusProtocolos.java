package com.vidacruz.asistente.infrastructure.rag;

import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Carga el corpus oficial ({@code classpath:protocolos/*.md}) y lo divide en un documento por <b>sección</b>.
 *
 * <p>Formato de cada archivo: cabecera con {@code id} y {@code titulo} entre {@code ---}, y secciones
 * {@code ## Nombre}. Dividir por sección hace que cada cita apunte a un fragmento concreto y verificable
 * ("PROTO-001 / Medidas iniciales"), y que el contexto entregado al LLM sea mínimo.
 *
 * <p>El texto que se vectoriza incluye el título del protocolo y el nombre de la sección para que preguntas
 * como "¿qué hago ante una crisis hipertensiva?" recuperen la sección aunque el cuerpo no repita esas palabras.
 */
public final class CorpusProtocolos {

    public static final String META_PROTOCOLO = "protocolo";
    public static final String META_TITULO = "titulo";
    public static final String META_SECCION = "seccion";
    public static final String META_CONTENIDO = "contenido";

    private CorpusProtocolos() {
    }

    public static List<Document> cargar(String patron) {
        try {
            Resource[] recursos = new PathMatchingResourcePatternResolver().getResources(patron);
            Arrays.sort(recursos, Comparator.comparing(Resource::getFilename));
            List<Document> documentos = new ArrayList<>();
            for (Resource recurso : recursos) {
                documentos.addAll(parsear(recurso.getContentAsString(StandardCharsets.UTF_8), recurso.getFilename()));
            }
            if (documentos.isEmpty()) {
                throw new IllegalStateException("El corpus de protocolos está vacío: " + patron);
            }
            return documentos;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el corpus de protocolos", e);
        }
    }

    static List<Document> parsear(String markdown, String origen) {
        String texto = markdown.replace("\r\n", "\n");
        String[] partes = texto.split("(?m)^---\\s*$", 3);
        if (partes.length < 3) {
            throw new IllegalStateException("Protocolo sin cabecera (---): " + origen);
        }
        Map<String, String> cabecera = new HashMap<>();
        for (String linea : partes[1].strip().split("\n")) {
            int dosPuntos = linea.indexOf(':');
            if (dosPuntos > 0) {
                cabecera.put(linea.substring(0, dosPuntos).strip(), linea.substring(dosPuntos + 1).strip());
            }
        }
        String id = requerido(cabecera, "id", origen);
        String titulo = requerido(cabecera, "titulo", origen);
        // Opcional: términos con que los médicos nombran la condición. Solo enriquecen el texto que se vectoriza
        // (recuperación); NUNCA se entregan al LLM ni forman parte del contenido clínico citado.
        String palabrasClave = cabecera.getOrDefault("palabras_clave", "");

        List<Document> secciones = new ArrayList<>();
        for (String bloque : partes[2].split("(?m)^## ")) {
            if (bloque.isBlank()) {
                continue;
            }
            int salto = bloque.indexOf('\n');
            String seccion = (salto < 0 ? bloque : bloque.substring(0, salto)).strip();
            String contenido = (salto < 0 ? "" : bloque.substring(salto + 1)).strip().replaceAll("\\s*\n\\s*", " ");
            if (contenido.isEmpty()) {
                continue;
            }
            String paraVectorizar = palabrasClave.isBlank()
                    ? "%s — %s. %s: %s".formatted(id, titulo, seccion, contenido)
                    : "%s — %s (%s). %s: %s".formatted(id, titulo, palabrasClave, seccion, contenido);
            secciones.add(Document.builder()
                    .id(id + "#" + seccion.toLowerCase().replace(' ', '-'))
                    .text(paraVectorizar)
                    .metadata(Map.of(
                            META_PROTOCOLO, id,
                            META_TITULO, titulo,
                            META_SECCION, seccion,
                            META_CONTENIDO, contenido))
                    .build());
        }
        if (secciones.isEmpty()) {
            throw new IllegalStateException("Protocolo sin secciones: " + origen);
        }
        return secciones;
    }

    private static String requerido(Map<String, String> cabecera, String campo, String origen) {
        String valor = cabecera.get(campo);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Falta '" + campo + "' en la cabecera de " + origen);
        }
        return valor;
    }
}
