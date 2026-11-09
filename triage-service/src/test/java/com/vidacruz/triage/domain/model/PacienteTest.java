package com.vidacruz.triage.domain.model;

import com.vidacruz.triage.domain.exception.DatosPacienteInvalidosException;
import com.vidacruz.triage.domain.exception.TransicionEstadoInvalidaException;
import com.vidacruz.triage.domain.service.ClasificadorTriage;
import com.vidacruz.triage.domain.service.PoliticaSla;
import com.vidacruz.triage.domain.service.QuejasAdministrativas;
import com.vidacruz.triage.domain.service.ValidadorRegistro;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PacienteTest {

    private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

    private final ValidadorRegistro validador = new ValidadorRegistro();
    private final ClasificadorTriage clasificador = new ClasificadorTriage(QuejasAdministrativas.porDefecto());
    private final PoliticaSla sla = PoliticaSla.porDefecto();

    /** Paciente E1 (CRITICO, SLA 5 min). */
    private Transicion registrarE1() {
        SolicitudRegistro e1 = new SolicitudRegistro("Paciente Uno", 58, 130, 180, 110, 97, 36.8, 8, 15,
                "Dolor torácico");
        return Paciente.registrar(UUID.randomUUID(), e1, validador, clasificador, sla, T0);
    }

    @Nested
    @DisplayName("Registro (RF-01)")
    class Registro {

        @Test
        void quedaRegistradoClasificadoYConSla() {
            Transicion t = registrarE1();
            Paciente p = t.paciente();

            assertThat(p.estado()).isEqualTo(EstadoPaciente.REGISTRADO);
            assertThat(p.nivel()).isEqualTo(NivelTriage.CRITICO);
            assertThat(p.sla()).isEqualTo(Duration.ofMinutes(5));
            assertThat(p.escalado()).isFalse();
            assertThat(p.fechaRegistro()).isEqualTo(T0);
            assertThat(p.criteriosClasificacion()).contains("FC > 120 (130)");
        }

        @Test
        @DisplayName("slaMinutos redondea hacia arriba un SLA configurado en segundos (nunca informa 0)")
        void slaMinutosRedondeaHaciaArriba() {
            Paciente p = registrarE1().paciente();
            Paciente conSla20s = new Paciente(p.id(), p.nombreCompleto(), p.edad(), p.signosVitales(),
                    p.quejaPrincipal(), p.nivel(), p.criteriosClasificacion(), Duration.ofSeconds(20), p.estado(),
                    p.escalado(), p.fechaRegistro(), p.fechaCambioEstado(), p.fechaEscalamiento(), p.version());

            assertThat(p.slaMinutos()).isEqualTo(5);
            assertThat(conSla20s.slaMinutos()).isEqualTo(1);
        }

        @Test
        void produceEventoPacienteRegistradoParaAuditoria() {
            EventoPaciente e = registrarE1().evento();

            assertThat(e.tipo()).isEqualTo(TipoEvento.PACIENTE_REGISTRADO);
            assertThat(e.tipo().codigo()).isEqualTo("paciente_registrado");
            assertThat(e.estadoAnterior()).isNull();
            assertThat(e.estadoNuevo()).isEqualTo(EstadoPaciente.REGISTRADO);
            assertThat(e.nivel()).isEqualTo(NivelTriage.CRITICO);
            assertThat(e.ocurridoEn()).isEqualTo(T0);
        }

        @Test
        void noRegistraDatosInvalidos() {
            SolicitudRegistro invalida = new SolicitudRegistro("Ana", 30, 300, 120, 80, 99, 36.5, 2, 15, "x");

            assertThatThrownBy(() -> Paciente.registrar(UUID.randomUUID(), invalida, validador, clasificador, sla, T0))
                    .isInstanceOf(DatosPacienteInvalidosException.class)
                    .hasMessageContaining("frecuenciaCardiaca");
        }
    }

    @Nested
    @DisplayName("Flujo de estados (RF-02)")
    class FlujoEstados {

        @Test
        void registradoAEnConsultaAAlta() {
            Paciente p = registrarE1().paciente();
            Instant t1 = T0.plusSeconds(120);
            Instant t2 = T0.plusSeconds(900);

            Transicion consulta = p.cambiarEstado(EstadoPaciente.EN_CONSULTA, t1);
            Transicion alta = consulta.paciente().cambiarEstado(EstadoPaciente.ALTA, t2);

            assertThat(consulta.evento().tipo()).isEqualTo(TipoEvento.PACIENTE_EN_CONSULTA);
            assertThat(consulta.evento().estadoAnterior()).isEqualTo(EstadoPaciente.REGISTRADO);
            assertThat(consulta.paciente().fechaCambioEstado()).isEqualTo(t1);
            assertThat(alta.evento().tipo()).isEqualTo(TipoEvento.PACIENTE_ALTA);
            assertThat(alta.paciente().estado()).isEqualTo(EstadoPaciente.ALTA);
        }

        @Test
        void altaDirectaDesdeRegistrado() {
            Transicion alta = registrarE1().paciente().cambiarEstado(EstadoPaciente.ALTA, T0.plusSeconds(60));

            assertThat(alta.paciente().estado()).isEqualTo(EstadoPaciente.ALTA);
            assertThat(alta.evento().estadoAnterior()).isEqualTo(EstadoPaciente.REGISTRADO);
        }

        @Test
        void transicionInvalidaLanzaExcepcionDescriptiva() {
            Paciente enAlta = registrarE1().paciente().cambiarEstado(EstadoPaciente.ALTA, T0).paciente();

            assertThatThrownBy(() -> enAlta.cambiarEstado(EstadoPaciente.EN_CONSULTA, T0))
                    .isInstanceOf(TransicionEstadoInvalidaException.class)
                    .hasMessageContaining("ALTA → EN_CONSULTA")
                    .hasMessageContaining("estado final");
        }

        @Test
        void tiempoEnEstadoActual() {
            Paciente p = registrarE1().paciente().cambiarEstado(EstadoPaciente.EN_CONSULTA, T0.plusSeconds(60)).paciente();

            assertThat(p.tiempoEnEstadoActual(T0.plusSeconds(360))).isEqualTo(Duration.ofMinutes(5));
        }
    }

    @Nested
    @DisplayName("Escalamiento por SLA (RF-03 / RN-03)")
    class Escalamiento {

        @Test
        void noEscalaAntesDeVencerElSla() {
            Paciente p = registrarE1().paciente();

            assertThat(p.escalarSiVencido(T0.plus(Duration.ofMinutes(5)))).isEmpty();
        }

        @Test
        void escalaCuandoSuperaElSla() {
            Paciente p = registrarE1().paciente();
            Instant ahora = T0.plus(Duration.ofMinutes(5)).plusSeconds(1);

            Transicion t = p.escalarSiVencido(ahora).orElseThrow();

            assertThat(t.paciente().escalado()).isTrue();
            assertThat(t.paciente().fechaEscalamiento()).isEqualTo(ahora);
            assertThat(t.paciente().estado()).isEqualTo(EstadoPaciente.REGISTRADO);
            assertThat(t.evento().tipo()).isEqualTo(TipoEvento.PACIENTE_ESCALADO);
            assertThat(t.evento().detalle()).contains("false → true");
        }

        @Test
        @DisplayName("Idempotencia: un paciente ya escalado no produce un segundo evento")
        void escalaUnaUnicaVez() {
            Paciente escalado = registrarE1().paciente()
                    .escalarSiVencido(T0.plus(Duration.ofMinutes(6))).orElseThrow().paciente();

            Optional<Transicion> segundoIntento = escalado.escalarSiVencido(T0.plus(Duration.ofMinutes(30)));

            assertThat(segundoIntento).isEmpty();
        }

        @Test
        void nuncaEscalaPacientesEnConsulta() {
            Paciente enConsulta = registrarE1().paciente()
                    .cambiarEstado(EstadoPaciente.EN_CONSULTA, T0.plusSeconds(60)).paciente();

            assertThat(enConsulta.escalarSiVencido(T0.plus(Duration.ofHours(5)))).isEmpty();
        }

        @Test
        void nuncaEscalaPacientesDeAlta() {
            Paciente alta = registrarE1().paciente().cambiarEstado(EstadoPaciente.ALTA, T0.plusSeconds(60)).paciente();

            assertThat(alta.escalarSiVencido(T0.plus(Duration.ofHours(5)))).isEmpty();
        }

        @Test
        void unPacienteEscaladoPuedeEntrarAConsultaYConservaElFlag() {
            Paciente escalado = registrarE1().paciente()
                    .escalarSiVencido(T0.plus(Duration.ofMinutes(6))).orElseThrow().paciente();

            Paciente enConsulta = escalado.cambiarEstado(EstadoPaciente.EN_CONSULTA, T0.plus(Duration.ofMinutes(7)))
                    .paciente();

            assertThat(enConsulta.escalado()).isTrue();
            assertThat(enConsulta.estado()).isEqualTo(EstadoPaciente.EN_CONSULTA);
        }
    }
}
