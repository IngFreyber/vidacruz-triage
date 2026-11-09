package com.vidacruz.triage.domain.service;

import java.text.Normalizer;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lista de quejas administrativas que degradan el nivel 4 a nivel 5 (RN-01).
 *
 * <p>Supuesto documentado: la comparación es por <b>igualdad tras normalizar</b> (minúsculas, sin tildes,
 * sin espacios repetidos ni puntuación final). No se usa "contiene" para no bajar la prioridad de un
 * paciente cuya queja mencione un término administrativo dentro de un texto clínico más amplio.
 */
public final class QuejasAdministrativas {

    public static final List<String> POR_DEFECTO = List.of(
            "documentos",
            "recolección de resultados",
            "renovación de recetario",
            "vacunación",
            "seguimiento");

    private final Set<String> normalizadas;

    public QuejasAdministrativas(Collection<String> quejas) {
        this.normalizadas = quejas.stream()
                .map(QuejasAdministrativas::normalizar)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static QuejasAdministrativas porDefecto() {
        return new QuejasAdministrativas(POR_DEFECTO);
    }

    public boolean esAdministrativa(String quejaPrincipal) {
        return quejaPrincipal != null && normalizadas.contains(normalizar(quejaPrincipal));
    }

    static String normalizar(String texto) {
        String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}¿¡]+$", "")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
