package com.vidacruz.triage.application.port.out;

import com.vidacruz.triage.domain.model.EntradaAuditoria;
import com.vidacruz.triage.domain.model.EventoPaciente;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Puerto de la auditoría clínica (RN-04). Solo inserción y lectura: no existe operación de borrado ni
 * de modificación, ni en este puerto ni en la base de datos (trigger de inmutabilidad).
 */
public interface AuditoriaRepository {

    Mono<Void> registrar(EventoPaciente evento);

    Flux<EntradaAuditoria> buscarPorPaciente(UUID pacienteId);
}
