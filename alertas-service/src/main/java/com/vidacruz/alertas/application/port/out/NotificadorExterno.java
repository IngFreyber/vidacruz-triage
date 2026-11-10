package com.vidacruz.alertas.application.port.out;

import com.vidacruz.alertas.domain.Alerta;
import reactor.core.publisher.Mono;

/**
 * Canal externo de notificación (p. ej. Microsoft Teams). Su falla nunca debe impedir el registro ni la
 * difusión SSE de la alerta.
 */
public interface NotificadorExterno {

    String nombre();

    Mono<Void> notificar(Alerta alerta);
}
