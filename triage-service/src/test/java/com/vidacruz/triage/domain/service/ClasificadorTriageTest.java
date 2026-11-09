package com.vidacruz.triage.domain.service;

import com.vidacruz.triage.domain.model.NivelTriage;
import com.vidacruz.triage.domain.model.SignosVitales;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClasificadorTriageTest {

    private final ClasificadorTriage clasificador = new ClasificadorTriage(QuejasAdministrativas.porDefecto());

    /** Signos "normales" que no cumplen ningún criterio de niveles 1-3. */
    private static SignosVitales normales() {
        return new SignosVitales(72, 120, 80, 99, 36.5, 2, 15);
    }

    @ParameterizedTest(name = "{0}: {8} → {9}")
    @DisplayName("Pacientes de referencia E1-E6 del enunciado")
    @CsvSource(delimiter = '|', textBlock = """
            E1 | 130 | 180 | 110 | 97 | 36.8 | 8 | 15 | Dolor torácico             | CRITICO
            E2 |  95 | 150 |  95 | 91 | 37.0 | 4 | 15 | Dificultad para respirar   | URGENTE
            E3 |  88 | 130 |  85 | 98 | 38.2 | 6 | 15 | Fiebre                     | URGENTE_2
            E4 |  72 | 120 |  80 | 99 | 36.5 | 2 | 15 | Herida por cortar          | NO_URGENTE
            E5 |  70 | 115 |  75 | 99 | 36.4 | 0 | 15 | Renovación de recetario    | NO_URGENTE_2
            E6 |  35 |  80 |  50 | 85 | 35.9 | 0 |  6 | Inconsciente               | CRITICO
            """)
    void clasificaPacientesDeReferencia(String caso, int fc, int sist, int dias, int spo2, double temp,
                                        int dolor, int gcs, String queja, NivelTriage esperado) {
        SignosVitales signos = new SignosVitales(fc, sist, dias, spo2, temp, dolor, gcs);

        ResultadoClasificacion resultado = clasificador.clasificar(signos, queja);

        assertThat(resultado.nivel()).as(caso).isEqualTo(esperado);
        assertThat(resultado.criterios()).as("criterios de " + caso).isNotEmpty();
    }

    @Test
    @DisplayName("Si se cumplen criterios de varios niveles, rige el de mayor gravedad")
    void rigeElNivelMasGrave() {
        // FC 100 (nivel 3) + SpO2 92 (nivel 2) + GCS 7 (nivel 1)
        SignosVitales s = new SignosVitales(100, 120, 80, 92, 38.6, 9, 7);

        ResultadoClasificacion r = clasificador.clasificar(s, "Dolor torácico");

        assertThat(r.nivel()).isEqualTo(NivelTriage.CRITICO);
        assertThat(r.criterios()).containsExactly("GCS ≤ 8 (7)");
    }

    @Test
    @DisplayName("Una queja administrativa no degrada a un paciente con criterios clínicos")
    void quejaAdministrativaNoAfectaNivelesClinicos() {
        SignosVitales fiebre = new SignosVitales(72, 120, 80, 99, 38.2, 0, 15);

        assertThat(clasificador.clasificar(fiebre, "Vacunación").nivel()).isEqualTo(NivelTriage.URGENTE_2);
    }

    @Nested
    @DisplayName("Límites de cada criterio")
    class Limites {

        @ParameterizedTest(name = "FC {0} → {1}")
        @CsvSource({"39, CRITICO", "40, NO_URGENTE", "89, NO_URGENTE", "90, URGENTE_2", "120, URGENTE_2", "121, CRITICO"})
        void frecuenciaCardiaca(int fc, NivelTriage esperado) {
            SignosVitales s = new SignosVitales(fc, 120, 80, 99, 36.5, 2, 15);
            assertThat(clasificador.clasificar(s, "x").nivel()).isEqualTo(esperado);
        }

        @ParameterizedTest(name = "SpO2 {0} → {1}")
        @CsvSource({"89, CRITICO", "90, URGENTE", "93, URGENTE", "94, NO_URGENTE"})
        void spo2(int spo2, NivelTriage esperado) {
            SignosVitales s = new SignosVitales(72, 120, 80, spo2, 36.5, 2, 15);
            assertThat(clasificador.clasificar(s, "x").nivel()).isEqualTo(esperado);
        }

        @ParameterizedTest(name = "P. sistólica {0} → {1}")
        @CsvSource({"89, CRITICO", "90, URGENTE", "100, URGENTE", "101, NO_URGENTE"})
        void presionSistolica(int sist, NivelTriage esperado) {
            SignosVitales s = new SignosVitales(72, sist, 60, 99, 36.5, 2, 15);
            assertThat(clasificador.clasificar(s, "x").nivel()).isEqualTo(esperado);
        }

        @ParameterizedTest(name = "GCS {0} → {1}")
        @CsvSource({"8, CRITICO", "9, URGENTE", "13, URGENTE", "14, NO_URGENTE"})
        void gcs(int gcs, NivelTriage esperado) {
            SignosVitales s = new SignosVitales(72, 120, 80, 99, 36.5, 2, gcs);
            assertThat(clasificador.clasificar(s, "x").nivel()).isEqualTo(esperado);
        }

        @ParameterizedTest(name = "Temperatura {0} → {1}")
        @CsvSource({"37.9, NO_URGENTE", "38.0, URGENTE_2", "38.4, URGENTE_2", "38.5, URGENTE", "34.0, NO_URGENTE"})
        void temperatura(double temp, NivelTriage esperado) {
            SignosVitales s = new SignosVitales(72, 120, 80, 99, temp, 2, 15);
            assertThat(clasificador.clasificar(s, "x").nivel()).isEqualTo(esperado);
        }

        @ParameterizedTest(name = "Dolor {0} → {1}")
        @CsvSource({"6, NO_URGENTE", "7, URGENTE_2", "10, URGENTE_2"})
        void dolor(int dolor, NivelTriage esperado) {
            SignosVitales s = new SignosVitales(72, 120, 80, 99, 36.5, dolor, 15);
            assertThat(clasificador.clasificar(s, "x").nivel()).isEqualTo(esperado);
        }
    }

    @ParameterizedTest(name = "\"{0}\" → nivel 5")
    @DisplayName("Todas las quejas administrativas llevan a NO_URGENTE_2 con signos normales")
    @CsvSource({"documentos", "Recolección de resultados", "RENOVACION DE RECETARIO", "vacunacion", " Seguimiento. "})
    void quejasAdministrativas(String queja) {
        assertThat(clasificador.clasificar(normales(), queja).nivel()).isEqualTo(NivelTriage.NO_URGENTE_2);
    }

    @Test
    @DisplayName("Una queja que solo menciona un término administrativo no es administrativa")
    void quejaQueContieneTerminoNoEsAdministrativa() {
        assertThat(clasificador.clasificar(normales(), "Seguimiento de herida infectada").nivel())
                .isEqualTo(NivelTriage.NO_URGENTE);
    }
}
