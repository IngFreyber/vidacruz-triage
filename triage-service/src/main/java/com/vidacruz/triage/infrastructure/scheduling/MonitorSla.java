package com.vidacruz.triage.infrastructure.scheduling;

import com.vidacruz.triage.application.service.EscalarPacientesService;
import com.vidacruz.triage.application.service.Tiempo;
import com.vidacruz.triage.infrastructure.config.EscalamientoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;

/**
 * Proceso de fondo de RF-03: evalúa periódicamente a los pacientes en {@code REGISTRADO} y escala a los que
 * superaron su SLA. Se eligió un scheduler reactivo (y no un consumidor de eventos) porque el vencimiento de
 * un SLA es la <i>ausencia</i> de un evento en el tiempo: hay que observar el reloj, no un mensaje.
 *
 * <p>Es seguro con varias réplicas: el caso de uso bloquea filas con {@code SKIP LOCKED}. Una evaluación en
 * curso descarta los ticks que llegan mientras tanto (backpressure) y un error nunca detiene el monitor.
 */
@Component
public class MonitorSla implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MonitorSla.class);

    private final EscalarPacientesService escalar;
    private final EscalamientoProperties props;
    private final Clock clock;

    private volatile Disposable suscripcion;

    public MonitorSla(EscalarPacientesService escalar, EscalamientoProperties props, Clock clock) {
        this.escalar = escalar;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public void start() {
        if (!props.habilitado()) {
            log.info("Monitor de SLA deshabilitado por configuración");
            return;
        }
        suscripcion = Flux.interval(props.intervalo(), props.intervalo())
                .onBackpressureDrop(tick -> log.debug("Evaluación de SLA en curso; se omite el tick {}", tick))
                .concatMap(tick -> evaluar(), 1)
                .subscribe();
        log.info("Monitor de SLA iniciado: intervalo={} lote={}", props.intervalo(), props.tamanoLote());
    }

    Mono<Integer> evaluar() {
        return Mono.defer(() -> escalar.escalarVencidos(Tiempo.ahora(clock)))
                .doOnNext(n -> {
                    if (n > 0) {
                        log.info("Evaluación de SLA: {} pacientes escalados", n);
                    }
                })
                .onErrorResume(e -> {
                    log.error("Falló la evaluación de SLA; se reintentará en el próximo ciclo", e);
                    return Mono.just(0);
                });
    }

    @Override
    public void stop() {
        if (suscripcion != null) {
            suscripcion.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        return suscripcion != null && !suscripcion.isDisposed();
    }
}
