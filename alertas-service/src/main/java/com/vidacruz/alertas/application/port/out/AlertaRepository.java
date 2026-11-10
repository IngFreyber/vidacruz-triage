package com.vidacruz.alertas.application.port.out;

import com.vidacruz.alertas.domain.Alerta;
import com.vidacruz.alertas.domain.EstadoAlerta;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Historial de alertas.
 */
public interface AlertaRepository {

    /**
     * Guarda la alerta solo si no existía (atómico).
     *
     * @return {@code true} si es nueva; {@code false} si ya se había registrado (reentrega del evento)
     */
    Mono<Boolean> guardarSiNueva(Alerta alerta);

    Mono<Alerta> buscar(UUID id);

    /** Alertas más recientes primero, opcionalmente filtradas por estado. */
    Flux<Alerta> listar(EstadoAlerta estado, int limite);

    /**
     * Marca la alerta como atendida si estaba pendiente (atómico: el primero que la toma queda registrado).
     * Si ya estaba atendida no la modifica. Vacío si no existe.
     */
    Mono<Atencion> marcarAtendida(UUID id, String atendidaPor, Instant cuando);

    /**
     * @param alerta    estado resultante
     * @param realizada {@code true} si esta llamada fue la que la marcó como atendida
     */
    record Atencion(Alerta alerta, boolean realizada) {
    }
}
