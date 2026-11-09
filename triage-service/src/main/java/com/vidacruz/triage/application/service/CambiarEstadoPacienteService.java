package com.vidacruz.triage.application.service;

import com.vidacruz.triage.application.port.out.PacienteRepository;
import com.vidacruz.triage.domain.exception.ConflictoConcurrenciaException;
import com.vidacruz.triage.domain.exception.PacienteNoEncontradoException;
import com.vidacruz.triage.domain.model.EstadoPaciente;
import com.vidacruz.triage.domain.model.Paciente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * RF-02: aplica una transición de estado. Ante una modificación concurrente (p. ej. el escalador marcó al
 * paciente en el mismo instante) relee y reintenta; la transición se vuelve a validar con el estado fresco.
 */
@Service
public class CambiarEstadoPacienteService {

    private static final Logger log = LoggerFactory.getLogger(CambiarEstadoPacienteService.class);
    private static final int MAX_REINTENTOS = 3;

    private final PacienteRepository pacientes;
    private final RegistroDeEventos eventos;
    private final TransactionalOperator tx;
    private final Clock clock;

    public CambiarEstadoPacienteService(PacienteRepository pacientes, RegistroDeEventos eventos,
                                        TransactionalOperator tx, Clock clock) {
        this.pacientes = pacientes;
        this.eventos = eventos;
        this.tx = tx;
        this.clock = clock;
    }

    public Mono<Paciente> cambiarEstado(UUID id, EstadoPaciente destino) {
        return Mono.defer(() -> intentar(id, destino))
                .retryWhen(Retry.backoff(MAX_REINTENTOS, Duration.ofMillis(20))
                        .filter(ConflictoConcurrenciaException.class::isInstance)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    private Mono<Paciente> intentar(UUID id, EstadoPaciente destino) {
        return pacientes.buscarPorId(id)
                .switchIfEmpty(Mono.error(() -> new PacienteNoEncontradoException(id)))
                .map(p -> p.cambiarEstado(destino, Tiempo.ahora(clock)))
                .flatMap(t -> pacientes.actualizar(t.paciente())
                        .then(eventos.registrar(t))
                        .thenReturn(t))
                .as(tx::transactional)
                .doOnNext(t -> log.info("Paciente id={} transición {} → {}",
                        id, t.evento().estadoAnterior(), t.evento().estadoNuevo()))
                .map(t -> t.paciente());
    }
}
