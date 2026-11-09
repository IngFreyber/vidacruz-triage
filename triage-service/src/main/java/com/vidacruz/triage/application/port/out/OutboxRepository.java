package com.vidacruz.triage.application.port.out;

import com.vidacruz.triage.domain.model.EventoPaciente;
import com.vidacruz.triage.domain.model.Paciente;
import reactor.core.publisher.Mono;

/**
 * Puerto del transactional outbox: el evento se guarda en la misma transacción que el cambio de estado y
 * un publicador independiente lo envía a Kafka. Así nunca hay estado persistido sin su evento, ni al revés.
 */
public interface OutboxRepository {

    Mono<Void> guardar(EventoPaciente evento, Paciente paciente);
}
