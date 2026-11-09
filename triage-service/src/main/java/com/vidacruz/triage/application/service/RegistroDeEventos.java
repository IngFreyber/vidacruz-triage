package com.vidacruz.triage.application.service;

import com.vidacruz.triage.application.port.out.AuditoriaRepository;
import com.vidacruz.triage.application.port.out.OutboxRepository;
import com.vidacruz.triage.domain.model.Transicion;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Registra los efectos colaterales comunes de toda transición: entrada de auditoría (RN-04) y evento en el
 * outbox. Debe ejecutarse dentro de la misma transacción que la escritura del paciente.
 */
@Component
public class RegistroDeEventos {

    private final AuditoriaRepository auditoria;
    private final OutboxRepository outbox;

    public RegistroDeEventos(AuditoriaRepository auditoria, OutboxRepository outbox) {
        this.auditoria = auditoria;
        this.outbox = outbox;
    }

    public Mono<Void> registrar(Transicion transicion) {
        return auditoria.registrar(transicion.evento())
                .then(outbox.guardar(transicion.evento(), transicion.paciente()));
    }
}
