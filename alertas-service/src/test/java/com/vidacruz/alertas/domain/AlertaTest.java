package com.vidacruz.alertas.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AlertaTest {

    @Test
    void construyeLaAlertaConEsperaYMensaje() {
        UUID evento = UUID.randomUUID();
        Alerta a = Alerta.desde(new EscalamientoPaciente(evento, UUID.randomUUID(), "Pedro Ruiz", "Dolor torácico",
                "CRITICO", 5, Instant.parse("2026-11-09T10:00:00Z"), Instant.parse("2026-11-09T10:07:30Z")));

        assertThat(a.id()).as("id de la alerta = eventId (idempotencia)").isEqualTo(evento);
        assertThat(a.minutosEspera()).isEqualTo(7);
        assertThat(a.estado()).isEqualTo(EstadoAlerta.PENDIENTE);
        assertThat(a.mensaje()).isEqualTo("Paciente CRITICO escalado: Pedro Ruiz lleva 7 min sin entrar a consulta "
                + "(SLA 5 min). Motivo: Dolor torácico.");
    }

    @Test
    void toleraDatosOpcionalesAusentes() {
        UUID paciente = UUID.randomUUID();
        Alerta a = Alerta.desde(new EscalamientoPaciente(UUID.randomUUID(), paciente, null, null, "URGENTE", 10,
                Instant.parse("2026-11-09T10:00:00Z"), Instant.parse("2026-11-09T10:11:00Z")));

        assertThat(a.nombrePaciente()).isEqualTo("Paciente " + paciente);
        assertThat(a.quejaPrincipal()).isEmpty();
        assertThat(a.mensaje()).doesNotContain("Motivo");
    }
}
