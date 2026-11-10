package com.vidacruz.triage.application.service;

import com.vidacruz.triage.application.port.out.MetricasTriage;
import com.vidacruz.triage.application.port.out.PacienteRepository;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.SolicitudRegistro;
import com.vidacruz.triage.domain.service.ClasificadorTriage;
import com.vidacruz.triage.domain.service.PoliticaSla;
import com.vidacruz.triage.domain.service.ValidadorRegistro;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/**
 * RF-01: valida, clasifica y registra al paciente; persiste paciente + auditoría + outbox de forma atómica.
 */
@Service
public class RegistrarPacienteService {

    private static final Logger log = LoggerFactory.getLogger(RegistrarPacienteService.class);

    private final PacienteRepository pacientes;
    private final RegistroDeEventos eventos;
    private final TransactionalOperator tx;
    private final ValidadorRegistro validador;
    private final ClasificadorTriage clasificador;
    private final PoliticaSla politicaSla;
    private final Clock clock;
    private final MetricasTriage metricas;

    public RegistrarPacienteService(PacienteRepository pacientes, RegistroDeEventos eventos, TransactionalOperator tx,
                                    ValidadorRegistro validador, ClasificadorTriage clasificador,
                                    PoliticaSla politicaSla, Clock clock, MetricasTriage metricas) {
        this.pacientes = pacientes;
        this.eventos = eventos;
        this.tx = tx;
        this.validador = validador;
        this.clasificador = clasificador;
        this.politicaSla = politicaSla;
        this.clock = clock;
        this.metricas = metricas;
    }

    public Mono<Paciente> registrar(SolicitudRegistro solicitud) {
        // La validación y clasificación son CPU puro y O(1): seguras en el event loop.
        return Mono.fromCallable(() -> Paciente.registrar(UUID.randomUUID(), solicitud, validador, clasificador,
                        politicaSla, Tiempo.ahora(clock)))
                .flatMap(t -> pacientes.insertar(t.paciente())
                        .then(eventos.registrar(t))
                        .thenReturn(t.paciente()))
                .as(tx::transactional)
                .doOnNext(p -> {
                    metricas.pacienteClasificado(p.nivel());
                    log.info("Paciente registrado id={} nivel={} slaMin={}", p.id(), p.nivel(), p.slaMinutos());
                });
    }
}
