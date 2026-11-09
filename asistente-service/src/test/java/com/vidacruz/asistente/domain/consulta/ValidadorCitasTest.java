package com.vidacruz.asistente.domain.consulta;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RN-05: validación de citas (anti-alucinación)")
class ValidadorCitasTest {

    private final ValidadorCitas validador = new ValidadorCitas();

    private final List<Fragmento> entregados = List.of(
            new Fragmento("PROTO-001", "Crisis Hipertensiva, manejo inicial", "Medidas iniciales", "Reconfirmar...", 0.68),
            new Fragmento("PROTO-001", "Crisis Hipertensiva, manejo inicial", "Contraindicaciones", "Nifedipino...", 0.63));

    @Test
    void respuestaConCitasValidasSeEntrega() {
        RespuestaClinica r = validador.validar(new RespuestaGenerada("Reconfirmar la presión tras 5 minutos.",
                List.of(new Fuente("PROTO-001", "Medidas iniciales")), false), entregados);

        assertThat(r.estado()).isEqualTo(EstadoRespuesta.RESPONDIDA);
        assertThat(r.fuentes()).containsExactly(new Fuente("PROTO-001", "Medidas iniciales"));
    }

    @Test
    @DisplayName("Las citas se normalizan a su forma canónica (mayúsculas, tildes, espacios) y se deduplican")
    void normalizaCitas() {
        RespuestaClinica r = validador.validar(new RespuestaGenerada("Texto", List.of(
                new Fuente("proto-001", "  medidas   INICIALES "),
                new Fuente("PROTO-001", "Medidas iniciales"),
                new Fuente("PROTO-001", "Contraindicaciónes")), false), entregados);

        assertThat(r.estado()).isEqualTo(EstadoRespuesta.RESPONDIDA);
        assertThat(r.fuentes()).containsExactly(
                new Fuente("PROTO-001", "Medidas iniciales"), new Fuente("PROTO-001", "Contraindicaciones"));
    }

    @Test
    @DisplayName("Una cita a un protocolo inexistente invalida la respuesta completa")
    void citaInexistenteDescartaTodo() {
        RespuestaClinica r = validador.validar(new RespuestaGenerada("Administrar 2 g de X.", List.of(
                new Fuente("PROTO-001", "Medidas iniciales"),
                new Fuente("PROTO-999", "Tratamiento")), false), entregados);

        assertThat(r.estado()).isEqualTo(EstadoRespuesta.RESPUESTA_DESCARTADA);
        assertThat(r.fuentes()).isEmpty();
        assertThat(r.respuesta()).doesNotContain("2 g").isEqualTo(RespuestaClinica.AVISO_DESCARTADA);
    }

    @Test
    @DisplayName("Citar una sección del corpus que NO se entregó al modelo también es inválido")
    void citaNoEntregadaDescarta() {
        RespuestaClinica r = validador.validar(new RespuestaGenerada("Texto",
                List.of(new Fuente("PROTO-003", "Rehidratación")), false), entregados);

        assertThat(r.estado()).isEqualTo(EstadoRespuesta.RESPUESTA_DESCARTADA);
    }

    @Test
    @DisplayName("Contenido clínico sin ninguna cita se descarta")
    void sinCitasDescarta() {
        RespuestaClinica r = validador.validar(new RespuestaGenerada("Dar paracetamol.", List.of(), false), entregados);

        assertThat(r.estado()).isEqualTo(EstadoRespuesta.RESPUESTA_DESCARTADA);
        assertThat(r.respuesta()).doesNotContain("paracetamol");
    }

    @Test
    @DisplayName("Si el modelo declara no tener información, solo se entrega el aviso (aunque haya escrito texto)")
    void sinInformacion() {
        RespuestaClinica r = validador.validar(new RespuestaGenerada("Quizás metformina...",
                List.of(new Fuente("PROTO-001", "Medidas iniciales")), true), entregados);

        assertThat(r.estado()).isEqualTo(EstadoRespuesta.SIN_PROTOCOLO);
        assertThat(r.respuesta()).isEqualTo(RespuestaClinica.AVISO_SIN_PROTOCOLO);
        assertThat(r.fuentes()).isEmpty();
    }

    @Test
    void respuestaVaciaSinCitasEsSinProtocolo() {
        assertThat(validador.validar(new RespuestaGenerada("", List.of(), false), entregados).estado())
                .isEqualTo(EstadoRespuesta.SIN_PROTOCOLO);
    }
}
