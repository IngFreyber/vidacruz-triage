package com.vidacruz.triage.domain.service;

import com.vidacruz.triage.domain.model.NivelTriage;
import com.vidacruz.triage.domain.model.SignosVitales;

import java.util.ArrayList;
import java.util.List;

/**
 * Motor de reglas RN-01. Evalúa los niveles en orden de gravedad (1 → 3); el primer nivel con al menos
 * un criterio cumplido determina la clasificación, lo que garantiza que rige siempre el de mayor gravedad.
 * Es una función pura: misma entrada, misma salida. Elimina la variabilidad entre evaluadores.
 *
 * <p>Supuesto documentado: la hipotermia (temperatura &lt; 38.0 °C) no está contemplada en RN-01 y no
 * eleva el nivel; tampoco la presión sistólica alta (&gt; 100 mmHg) por sí sola.
 */
public final class ClasificadorTriage {

    private final QuejasAdministrativas quejasAdministrativas;

    public ClasificadorTriage(QuejasAdministrativas quejasAdministrativas) {
        this.quejasAdministrativas = quejasAdministrativas;
    }

    public ResultadoClasificacion clasificar(SignosVitales s, String quejaPrincipal) {
        List<String> criterios = criteriosCritico(s);
        if (!criterios.isEmpty()) {
            return new ResultadoClasificacion(NivelTriage.CRITICO, criterios);
        }
        criterios = criteriosUrgente(s);
        if (!criterios.isEmpty()) {
            return new ResultadoClasificacion(NivelTriage.URGENTE, criterios);
        }
        criterios = criteriosUrgente2(s);
        if (!criterios.isEmpty()) {
            return new ResultadoClasificacion(NivelTriage.URGENTE_2, criterios);
        }
        if (quejasAdministrativas.esAdministrativa(quejaPrincipal)) {
            return new ResultadoClasificacion(NivelTriage.NO_URGENTE_2,
                    List.of("Sin criterios de niveles 1-3 y queja administrativa: " + quejaPrincipal.strip()));
        }
        return new ResultadoClasificacion(NivelTriage.NO_URGENTE, List.of("Sin criterios de niveles 1-3"));
    }

    private static List<String> criteriosCritico(SignosVitales s) {
        List<String> c = new ArrayList<>();
        if (s.frecuenciaCardiaca() < 40) c.add("FC < 40 (" + s.frecuenciaCardiaca() + ")");
        if (s.frecuenciaCardiaca() > 120) c.add("FC > 120 (" + s.frecuenciaCardiaca() + ")");
        if (s.spo2() < 90) c.add("SpO2 < 90 (" + s.spo2() + ")");
        if (s.presionSistolica() < 90) c.add("P. sistólica < 90 (" + s.presionSistolica() + ")");
        if (s.gcs() <= 8) c.add("GCS ≤ 8 (" + s.gcs() + ")");
        return c;
    }

    private static List<String> criteriosUrgente(SignosVitales s) {
        List<String> c = new ArrayList<>();
        if (entre(s.spo2(), 90, 93)) c.add("90 ≤ SpO2 ≤ 93 (" + s.spo2() + ")");
        if (entre(s.presionSistolica(), 90, 100)) c.add("90 ≤ P. sistólica ≤ 100 (" + s.presionSistolica() + ")");
        if (s.temperatura() >= 38.5) c.add("Temperatura ≥ 38.5 (" + s.temperatura() + ")");
        if (entre(s.gcs(), 9, 13)) c.add("9 ≤ GCS ≤ 13 (" + s.gcs() + ")");
        return c;
    }

    private static List<String> criteriosUrgente2(SignosVitales s) {
        List<String> c = new ArrayList<>();
        if (entre(s.frecuenciaCardiaca(), 90, 120)) c.add("90 ≤ FC ≤ 120 (" + s.frecuenciaCardiaca() + ")");
        if (s.temperatura() >= 38.0 && s.temperatura() < 38.5) c.add("38.0 ≤ Temperatura < 38.5 (" + s.temperatura() + ")");
        if (s.dolor() >= 7) c.add("Dolor ≥ 7 (" + s.dolor() + ")");
        return c;
    }

    private static boolean entre(int valor, int min, int max) {
        return valor >= min && valor <= max;
    }
}
