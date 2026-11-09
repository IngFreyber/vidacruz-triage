package com.vidacruz.asistente.infrastructure.redis;

import com.vidacruz.asistente.IntegracionBase;
import com.vidacruz.asistente.application.port.out.VistaUrgenciasRepository;
import com.vidacruz.asistente.domain.urgencias.EstadoUrgencias;
import com.vidacruz.asistente.domain.urgencias.EventoTriaje;
import com.vidacruz.asistente.domain.urgencias.NivelTriage;
import com.vidacruz.asistente.domain.urgencias.ResultadoAplicacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.vidacruz.asistente.domain.urgencias.NivelTriage.CRITICO;
import static com.vidacruz.asistente.domain.urgencias.NivelTriage.URGENTE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Semántica del script Lua contra un Redis real. Se comparan deltas respecto del estado previo porque la
 * vista es global y la comparten las demás clases de test.
 */
@DisplayName("Vista de urgencias en Redis: atomicidad e idempotencia")
class VistaUrgenciasRedisTest extends IntegracionBase {

    private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

    @Autowired
    VistaUrgenciasRepository vista;

    private EstadoUrgencias estado() {
        return vista.consultar().block();
    }

    private ResultadoAplicacion aplicar(EventoTriaje e) {
        return vista.aplicar(e).block();
    }

    private static EventoTriaje evento(UUID paciente, String tipo, NivelTriage nivel, String estado, int segundo) {
        return new EventoTriaje(UUID.randomUUID(), tipo, paciente, nivel, estado, T0.plusSeconds(segundo));
    }

    private static long delta(EstadoUrgencias antes, EstadoUrgencias despues, NivelTriage nivel) {
        return despues.porNivel().get(nivel) - antes.porNivel().get(nivel);
    }

    @Test
    @DisplayName("Ciclo completo: registro suma, consulta mantiene, alta resta")
    void cicloCompleto() {
        UUID p = UUID.randomUUID();
        EstadoUrgencias inicio = estado();

        assertThat(aplicar(evento(p, "paciente_registrado", CRITICO, "REGISTRADO", 0)))
                .isEqualTo(ResultadoAplicacion.APLICADO);
        assertThat(delta(inicio, estado(), CRITICO)).isEqualTo(1);

        aplicar(evento(p, "paciente_escalado", CRITICO, "REGISTRADO", 300));
        aplicar(evento(p, "paciente_en_consulta", CRITICO, "EN_CONSULTA", 400));
        EstadoUrgencias enConsulta = estado();
        assertThat(delta(inicio, enConsulta, CRITICO)).as("EN_CONSULTA sigue activo").isEqualTo(1);
        assertThat(enConsulta.totalActivos() - inicio.totalActivos()).isEqualTo(1);
        assertThat(enConsulta.actualizadoEn()).isNotNull();

        aplicar(evento(p, "paciente_alta", CRITICO, "ALTA", 900));
        assertThat(delta(inicio, estado(), CRITICO)).isZero();
    }

    @Test
    @DisplayName("Alta directa desde REGISTRADO descuenta del nivel correcto")
    void altaDirecta() {
        UUID p = UUID.randomUUID();
        EstadoUrgencias inicio = estado();

        aplicar(evento(p, "paciente_registrado", URGENTE, "REGISTRADO", 0));
        aplicar(evento(p, "paciente_alta", URGENTE, "ALTA", 60));

        EstadoUrgencias fin = estado();
        assertThat(delta(inicio, fin, URGENTE)).isZero();
        assertThat(fin.totalActivos()).isEqualTo(inicio.totalActivos());
    }

    @Test
    @DisplayName("Un evento reentregado (mismo eventId) no altera los contadores")
    void duplicadoNoCuentaDosVeces() {
        UUID p = UUID.randomUUID();
        EventoTriaje registro = evento(p, "paciente_registrado", CRITICO, "REGISTRADO", 0);
        EstadoUrgencias inicio = estado();

        assertThat(aplicar(registro)).isEqualTo(ResultadoAplicacion.APLICADO);
        assertThat(aplicar(registro)).isEqualTo(ResultadoAplicacion.DUPLICADO);
        assertThat(aplicar(registro)).isEqualTo(ResultadoAplicacion.DUPLICADO);

        assertThat(delta(inicio, estado(), CRITICO)).isEqualTo(1);
    }

    @Test
    @DisplayName("Entregas concurrentes del mismo evento se aplican una sola vez (script atómico)")
    void entregasConcurrentes() {
        UUID p = UUID.randomUUID();
        EventoTriaje registro = evento(p, "paciente_registrado", URGENTE, "REGISTRADO", 0);
        EstadoUrgencias inicio = estado();

        List<ResultadoAplicacion> resultados = Flux.range(0, 20)
                .flatMap(i -> vista.aplicar(registro))
                .collectList().block();

        assertThat(resultados).filteredOn(r -> r == ResultadoAplicacion.APLICADO).hasSize(1);
        assertThat(delta(inicio, estado(), URGENTE)).isEqualTo(1);
    }

    @Test
    @DisplayName("Ningún evento posterior reactiva a un paciente dado de alta")
    void altaEsTerminal() {
        UUID p = UUID.randomUUID();
        aplicar(evento(p, "paciente_registrado", CRITICO, "REGISTRADO", 0));
        aplicar(evento(p, "paciente_alta", CRITICO, "ALTA", 60));
        EstadoUrgencias trasAlta = estado();

        assertThat(aplicar(evento(p, "paciente_escalado", CRITICO, "REGISTRADO", 120)))
                .isEqualTo(ResultadoAplicacion.PACIENTE_FINALIZADO);
        assertThat(delta(trasAlta, estado(), CRITICO)).isZero();
    }

    @Test
    @DisplayName("Un evento más antiguo que el último aplicado se descarta")
    void eventoObsoleto() {
        UUID p = UUID.randomUUID();
        aplicar(evento(p, "paciente_registrado", URGENTE, "REGISTRADO", 100));
        aplicar(evento(p, "paciente_en_consulta", URGENTE, "EN_CONSULTA", 200));
        EstadoUrgencias antes = estado();

        assertThat(aplicar(evento(p, "paciente_registrado", URGENTE, "REGISTRADO", 50)))
                .isEqualTo(ResultadoAplicacion.OBSOLETO);
        assertThat(delta(antes, estado(), URGENTE)).isZero();
    }

    @Test
    @DisplayName("La consulta siempre devuelve los cinco niveles")
    void cincoNiveles() {
        assertThat(estado().porNivel()).containsOnlyKeys(NivelTriage.values());
    }
}
