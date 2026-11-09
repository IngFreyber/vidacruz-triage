package com.vidacruz.asistente.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.asistente.application.service.EstadoUrgenciasService;
import com.vidacruz.asistente.domain.urgencias.EventoTriaje;
import com.vidacruz.asistente.domain.urgencias.NivelTriage;
import com.vidacruz.asistente.infrastructure.config.VistaUrgenciasProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("Lectura del contrato de triaje.eventos")
class ConsumidorEventosTriajeTest {

    private final ConsumidorEventosTriaje consumidor = new ConsumidorEventosTriaje(
            mock(EstadoUrgenciasService.class),
            new VistaUrgenciasProperties("triaje.eventos", "localhost:9092", "g", false,
                    Duration.ofDays(7), Duration.ofDays(7)),
            new ObjectMapper().findAndRegisterModules());

    @Test
    void leeUnEventoV1EIgnoraCamposAdicionales() {
        EventoTriaje e = consumidor.parsear("""
                {"eventId":"6749c0d7-da87-407e-a9b6-2bd36efbd81f","eventType":"paciente_registrado",
                 "eventVersion":1,"occurredAt":"2026-11-09T21:31:09.463Z",
                 "pacienteId":"d4021d68-2435-491f-bab6-f0e405218086","nombreCompleto":"Maria Gomez",
                 "nivel":"URGENTE","estadoNuevo":"REGISTRADO","slaMinutos":10,"campoFuturo":"x"}
                """);

        assertThat(e.nivel()).isEqualTo(NivelTriage.URGENTE);
        assertThat(e.estadoNuevo()).isEqualTo("REGISTRADO");
        assertThat(e.tipo()).isEqualTo("paciente_registrado");
        assertThat(e.dejaActivo()).isTrue();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "esto no es json",
            "{}",
            "{\"eventId\":\"6749c0d7-da87-407e-a9b6-2bd36efbd81f\",\"eventType\":\"paciente_registrado\","
                    + "\"occurredAt\":\"2026-11-09T21:31:09Z\",\"pacienteId\":\"d4021d68-2435-491f-bab6-f0e405218086\","
                    + "\"nivel\":\"GRAVISIMO\",\"estadoNuevo\":\"REGISTRADO\"}",
            "{\"eventId\":\"6749c0d7-da87-407e-a9b6-2bd36efbd81f\",\"eventType\":\"paciente_registrado\","
                    + "\"occurredAt\":\"2026-11-09T21:31:09Z\",\"pacienteId\":\"d4021d68-2435-491f-bab6-f0e405218086\","
                    + "\"estadoNuevo\":\"REGISTRADO\"}"})
    @DisplayName("Un mensaje fuera de contrato se identifica como poison message")
    void mensajesFueraDeContrato(String json) {
        assertThatThrownBy(() -> consumidor.parsear(json)).isInstanceOf(MensajeInvalidoException.class);
    }
}
