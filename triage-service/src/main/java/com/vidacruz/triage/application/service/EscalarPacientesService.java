package com.vidacruz.triage.application.service;

import com.vidacruz.triage.application.port.out.PacienteRepository;
import com.vidacruz.triage.domain.model.Transicion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * RF-03 / RN-03: escala a los pacientes que superaron su SLA sin entrar a consulta.
 *
 * <p>La idempotencia se garantiza en tres capas:
 * <ol>
 *   <li><b>Consulta</b>: solo selecciona {@code REGISTRADO} y no escalados, con {@code FOR UPDATE SKIP LOCKED}
 *       (dos monitores concurrentes nunca toman el mismo paciente).</li>
 *   <li><b>Dominio</b>: {@code Paciente.escalarSiVencido} vuelve a validar estado, flag y SLA.</li>
 *   <li><b>Escritura</b>: actualización condicionada a la versión leída.</li>
 * </ol>
 * Flag, auditoría y evento {@code paciente_escalado} se escriben en la misma transacción: un paciente se
 * escala una única vez y nunca sin su auditoría ni su evento.
 */
public class EscalarPacientesService {

    private static final Logger log = LoggerFactory.getLogger(EscalarPacientesService.class);

    private final PacienteRepository pacientes;
    private final RegistroDeEventos eventos;
    private final TransactionalOperator tx;
    private final int tamanoLote;

    public EscalarPacientesService(PacienteRepository pacientes, RegistroDeEventos eventos, TransactionalOperator tx,
                                   int tamanoLote) {
        this.pacientes = pacientes;
        this.eventos = eventos;
        this.tx = tx;
        this.tamanoLote = tamanoLote;
    }

    /**
     * Escala todos los pacientes vencidos a la fecha {@code ahora}, en lotes transaccionales.
     *
     * @return cantidad de pacientes escalados
     */
    public Mono<Integer> escalarVencidos(Instant ahora) {
        return escalarLote(ahora)
                .expand(escalados -> escalados == tamanoLote ? escalarLote(ahora) : Mono.empty())
                .reduce(0, Integer::sum);
    }

    private Mono<Integer> escalarLote(Instant ahora) {
        return pacientes.bloquearConSlaVencido(ahora, tamanoLote)
                .concatMap(p -> Mono.justOrEmpty(p.escalarSiVencido(ahora)))
                .concatMap(t -> pacientes.actualizar(t.paciente())
                        .then(eventos.registrar(t))
                        .thenReturn(t))
                .doOnNext(this::registrarLog)
                .count()
                .map(Long::intValue)
                .as(tx::transactional);
    }

    private void registrarLog(Transicion t) {
        log.warn("Paciente ESCALADO id={} nivel={} slaMin={} registro={}",
                t.paciente().id(), t.paciente().nivel(), t.paciente().slaMinutos(), t.paciente().fechaRegistro());
    }
}
