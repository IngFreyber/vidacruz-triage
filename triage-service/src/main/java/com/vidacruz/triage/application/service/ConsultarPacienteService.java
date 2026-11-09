package com.vidacruz.triage.application.service;

import com.vidacruz.triage.application.port.out.AuditoriaRepository;
import com.vidacruz.triage.application.port.out.PacienteRepository;
import com.vidacruz.triage.domain.exception.PacienteNoEncontradoException;
import com.vidacruz.triage.domain.model.EntradaAuditoria;
import com.vidacruz.triage.domain.model.Paciente;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Consultas de solo lectura: paciente (RF-02) y su bitácora de auditoría (RN-04).
 */
@Service
public class ConsultarPacienteService {

    private final PacienteRepository pacientes;
    private final AuditoriaRepository auditoria;

    public ConsultarPacienteService(PacienteRepository pacientes, AuditoriaRepository auditoria) {
        this.pacientes = pacientes;
        this.auditoria = auditoria;
    }

    public Mono<Paciente> buscar(UUID id) {
        return pacientes.buscarPorId(id)
                .switchIfEmpty(Mono.error(() -> new PacienteNoEncontradoException(id)));
    }

    public Flux<EntradaAuditoria> auditoria(UUID id) {
        return buscar(id).flatMapMany(p -> auditoria.buscarPorPaciente(p.id()));
    }
}
