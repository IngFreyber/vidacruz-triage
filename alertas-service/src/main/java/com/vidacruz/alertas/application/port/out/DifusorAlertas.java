package com.vidacruz.alertas.application.port.out;

import com.vidacruz.alertas.domain.Alerta;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Difusión en tiempo real a los tableros conectados (SSE), coherente entre todas las réplicas del servicio.
 */
public interface DifusorAlertas {

    /** Publica una alerta nueva o un cambio de estado (p. ej. atendida) a todos los suscriptores. */
    Mono<Void> difundir(Alerta alerta);

    /** Flujo caliente de alertas para un suscriptor. */
    Flux<Alerta> suscribir();
}
