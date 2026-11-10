package com.vidacruz.alertas.application.service;

import com.vidacruz.alertas.application.port.out.AlertaRepository;
import com.vidacruz.alertas.application.port.out.DifusorAlertas;
import com.vidacruz.alertas.application.port.out.NotificadorExterno;
import com.vidacruz.alertas.domain.Alerta;
import com.vidacruz.alertas.domain.EscalamientoPaciente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * RF-06: convierte un {@code paciente_escalado} en una alerta para el equipo médico.
 *
 * <ol>
 *   <li>Registra la alerta de forma idempotente (id = eventId): una reentrega no duplica alertas ni avisos.</li>
 *   <li>La difunde en tiempo real a los tableros conectados (SSE, todas las réplicas).</li>
 *   <li>La envía a los canales externos configurados (Teams). Una falla externa se registra pero no impide
 *       ni deshace los pasos anteriores.</li>
 * </ol>
 */
@Service
public class ProcesarEscalamientoService {

    private static final Logger log = LoggerFactory.getLogger(ProcesarEscalamientoService.class);

    private final AlertaRepository alertas;
    private final DifusorAlertas difusor;
    private final List<NotificadorExterno> notificadores;

    public ProcesarEscalamientoService(AlertaRepository alertas, DifusorAlertas difusor,
                                       List<NotificadorExterno> notificadores) {
        this.alertas = alertas;
        this.difusor = difusor;
        this.notificadores = notificadores;
    }

    /**
     * @return {@code true} si se emitió una alerta nueva; {@code false} si era una reentrega
     */
    public Mono<Boolean> procesar(EscalamientoPaciente escalamiento) {
        Alerta alerta = Alerta.desde(escalamiento);
        return alertas.guardarSiNueva(alerta)
                .flatMap(nueva -> {
                    if (!nueva) {
                        log.info("Escalamiento {} ya alertado (reentrega); se omite", escalamiento.eventId());
                        return Mono.just(false);
                    }
                    log.warn("ALERTA emitida id={} paciente={} nivel={} espera={}min", alerta.id(),
                            alerta.pacienteId(), alerta.nivel(), alerta.minutosEspera());
                    return difusor.difundir(alerta)
                            .onErrorResume(e -> {
                                log.error("No se pudo difundir la alerta {} por SSE: {}", alerta.id(), e.toString());
                                return Mono.empty();
                            })
                            .then(notificarExternos(alerta))
                            .thenReturn(true);
                });
    }

    private Mono<Void> notificarExternos(Alerta alerta) {
        return Flux.fromIterable(notificadores)
                .flatMap(n -> n.notificar(alerta)
                        .doOnSuccess(v -> log.info("Alerta {} enviada por {}", alerta.id(), n.nombre()))
                        .onErrorResume(e -> {
                            log.error("Canal {} no pudo entregar la alerta {} (queda en historial y SSE): {}",
                                    n.nombre(), alerta.id(), e.toString());
                            return Mono.empty();
                        }))
                .then();
    }
}
