package com.vidacruz.triage.domain.service;

import com.vidacruz.triage.domain.exception.DatosPacienteInvalidosException;
import com.vidacruz.triage.domain.exception.DatosPacienteInvalidosException.Violacion;
import com.vidacruz.triage.domain.model.SolicitudRegistro;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ValidadorRegistroTest {

    private final ValidadorRegistro validador = new ValidadorRegistro();

    static SolicitudRegistro valida() {
        return new SolicitudRegistro("Ana María Pérez", 45, 72, 120, 80, 99, 36.5, 2, 15, "Herida por cortar");
    }

    @Test
    void aceptaUnaSolicitudValida() {
        assertThatCode(() -> validador.validar(valida())).doesNotThrowAnyException();
    }

    @Test
    void aceptaLosLimitesInclusivos() {
        SolicitudRegistro minimos = new SolicitudRegistro("Ana", 0, 20, 60, 30, 70, 34.0, 0, 3, "x");
        SolicitudRegistro maximos = new SolicitudRegistro("A".repeat(100), 120, 250, 260, 159, 100, 42.0, 10, 15, "x");

        assertThatCode(() -> validador.validar(minimos)).doesNotThrowAnyException();
        assertThatCode(() -> validador.validar(maximos)).doesNotThrowAnyException();
    }

    static Stream<Arguments> fueraDeRango() {
        return Stream.of(
                caso("frecuenciaCardiaca", s -> copia(s, b -> b.fc = 19)),
                caso("frecuenciaCardiaca", s -> copia(s, b -> b.fc = 251)),
                caso("presionSistolica", s -> copia(s, b -> { b.sist = 59; b.dias = 40; })),
                caso("presionSistolica", s -> copia(s, b -> b.sist = 261)),
                caso("presionDiastolica", s -> copia(s, b -> b.dias = 29)),
                caso("presionDiastolica", s -> copia(s, b -> { b.sist = 200; b.dias = 161; })),
                caso("spo2", s -> copia(s, b -> b.spo2 = 69)),
                caso("spo2", s -> copia(s, b -> b.spo2 = 101)),
                caso("temperatura", s -> copia(s, b -> b.temp = 33.9)),
                caso("temperatura", s -> copia(s, b -> b.temp = 42.1)),
                caso("dolor", s -> copia(s, b -> b.dolor = -1)),
                caso("dolor", s -> copia(s, b -> b.dolor = 11)),
                caso("gcs", s -> copia(s, b -> b.gcs = 2)),
                caso("gcs", s -> copia(s, b -> b.gcs = 16)),
                caso("edad", s -> copia(s, b -> b.edad = -1)),
                caso("edad", s -> copia(s, b -> b.edad = 121)),
                caso("nombreCompleto", s -> copia(s, b -> b.nombre = "Al")),
                caso("nombreCompleto", s -> copia(s, b -> b.nombre = "A".repeat(101))),
                caso("nombreCompleto", s -> copia(s, b -> b.nombre = "   ")),
                caso("quejaPrincipal", s -> copia(s, b -> b.queja = null)),
                caso("spo2", s -> copia(s, b -> b.spo2 = null)));
    }

    @ParameterizedTest(name = "[{index}] campo inválido: {0}")
    @MethodSource("fueraDeRango")
    void rechazaValoresFueraDeRangoIndicandoElCampo(String campo, UnaryOperator<SolicitudRegistro> mutacion) {
        DatosPacienteInvalidosException ex = catchThrowableOfType(DatosPacienteInvalidosException.class,
                () -> validador.validar(mutacion.apply(valida())));

        assertThat(ex).isNotNull();
        assertThat(ex.violaciones()).extracting(Violacion::campo).containsExactly(campo);
        assertThat(ex.getMessage()).contains(campo);
    }

    @Test
    @DisplayName("La diastólica debe ser siempre menor que la sistólica")
    void diastolicaMenorQueSistolica() {
        SolicitudRegistro igual = copia(valida(), b -> { b.sist = 100; b.dias = 100; });

        DatosPacienteInvalidosException ex = catchThrowableOfType(DatosPacienteInvalidosException.class,
                () -> validador.validar(igual));

        assertThat(ex.violaciones()).singleElement()
                .satisfies(v -> {
                    assertThat(v.campo()).isEqualTo("presionDiastolica");
                    assertThat(v.mensaje()).contains("menor que la presión sistólica");
                });
    }

    @Test
    @DisplayName("Reporta todas las violaciones en una sola respuesta")
    void reportaTodasLasViolaciones() {
        SolicitudRegistro mala = new SolicitudRegistro(null, 200, 300, 120, 80, 99, 36.5, 2, 15, "x");

        DatosPacienteInvalidosException ex = catchThrowableOfType(DatosPacienteInvalidosException.class,
                () -> validador.validar(mala));

        assertThat(ex.violaciones()).extracting(Violacion::campo)
                .containsExactly("nombreCompleto", "edad", "frecuenciaCardiaca");
    }

    // --- utilidades para mutar una solicitud válida ---

    private static Arguments caso(String campo, UnaryOperator<SolicitudRegistro> mutacion) {
        return Arguments.of(campo, mutacion);
    }

    private static SolicitudRegistro copia(SolicitudRegistro s, java.util.function.Consumer<Builder> cambios) {
        Builder b = new Builder(s);
        cambios.accept(b);
        return new SolicitudRegistro(b.nombre, b.edad, b.fc, b.sist, b.dias, b.spo2, b.temp, b.dolor, b.gcs, b.queja);
    }

    private static final class Builder {
        String nombre; Integer edad; Integer fc; Integer sist; Integer dias; Integer spo2; Double temp;
        Integer dolor; Integer gcs; String queja;

        Builder(SolicitudRegistro s) {
            nombre = s.nombreCompleto(); edad = s.edad(); fc = s.frecuenciaCardiaca(); sist = s.presionSistolica();
            dias = s.presionDiastolica(); spo2 = s.spo2(); temp = s.temperatura(); dolor = s.dolor(); gcs = s.gcs();
            queja = s.quejaPrincipal();
        }
    }
}
