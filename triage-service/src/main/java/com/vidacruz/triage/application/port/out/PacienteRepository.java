package com.vidacruz.triage.application.port.out;

import com.vidacruz.triage.domain.model.Paciente;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Puerto de persistencia del agregado {@link Paciente}.
 */
public interface PacienteRepository {

    Mono<Void> insertar(Paciente paciente);

    Mono<Paciente> buscarPorId(UUID id);

    /**
     * Actualiza estado y flags con control optimista: usa {@code paciente.version()} como versión esperada.
     * Emite {@link com.vidacruz.triage.domain.exception.ConflictoConcurrenciaException} si otra operación
     * modificó el paciente en el intermedio.
     */
    Mono<Void> actualizar(Paciente paciente);

    /**
     * Bloquea (dentro de la transacción en curso) a los pacientes en {@code REGISTRADO}, no escalados y con
     * SLA vencido a la fecha {@code ahora}. Las filas ya bloqueadas por otra transacción se omiten, lo que
     * permite varias réplicas del monitor sin escalar dos veces al mismo paciente.
     */
    Flux<Paciente> bloquearConSlaVencido(Instant ahora, int limite);
}
