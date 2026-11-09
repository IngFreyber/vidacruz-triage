package com.vidacruz.asistente.application.port.out;

import com.vidacruz.asistente.domain.urgencias.EstadoUrgencias;
import com.vidacruz.asistente.domain.urgencias.EventoTriaje;
import com.vidacruz.asistente.domain.urgencias.ResultadoAplicacion;
import reactor.core.publisher.Mono;

/**
 * Vista materializada del estado de la urgencia, construida exclusivamente desde {@code triaje.eventos}.
 */
public interface VistaUrgenciasRepository {

    /**
     * Aplica un evento de forma atómica e idempotente (deduplicación por eventId incluida).
     */
    Mono<ResultadoAplicacion> aplicar(EventoTriaje evento);

    Mono<EstadoUrgencias> consultar();
}
