package com.vidacruz.alertas.application.service;

import com.vidacruz.alertas.application.port.out.AlertaRepository;
import com.vidacruz.alertas.application.port.out.DifusorAlertas;
import com.vidacruz.alertas.domain.Alerta;
import com.vidacruz.alertas.domain.AlertaNoEncontradaException;
import com.vidacruz.alertas.domain.EstadoAlerta;
import com.vidacruz.alertas.domain.SolicitudInvalidaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Consulta del historial y confirmación de atención (acknowledge) de alertas.
 */
@Service
public class GestionAlertasService {

    private static final Logger log = LoggerFactory.getLogger(GestionAlertasService.class);
    static final int LIMITE_MAXIMO = 200;

    private final AlertaRepository alertas;
    private final DifusorAlertas difusor;
    private final Clock clock;

    public GestionAlertasService(AlertaRepository alertas, DifusorAlertas difusor, Clock clock) {
        this.alertas = alertas;
        this.difusor = difusor;
        this.clock = clock;
    }

    public Flux<Alerta> listar(EstadoAlerta estado, int limite) {
        if (limite < 1 || limite > LIMITE_MAXIMO) {
            return Flux.error(new SolicitudInvalidaException("limite",
                    "debe estar entre 1 y %d; recibido %d".formatted(LIMITE_MAXIMO, limite)));
        }
        return alertas.listar(estado, limite);
    }

    public Mono<Alerta> buscar(UUID id) {
        return alertas.buscar(id).switchIfEmpty(Mono.error(() -> new AlertaNoEncontradaException(id)));
    }

    /**
     * Marca la alerta como atendida. Es idempotente: si ya estaba atendida se devuelve sin cambios
     * (conserva quién la tomó primero) y no se vuelve a difundir.
     */
    public Mono<Alerta> atender(UUID id, String atendidaPor) {
        String responsable = atendidaPor == null ? "" : atendidaPor.strip();
        if (responsable.length() < 3 || responsable.length() > 100) {
            return Mono.error(new SolicitudInvalidaException("atendidaPor",
                    "debe tener entre 3 y 100 caracteres (nombre o identificador del profesional)"));
        }
        Instant ahora = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
        return alertas.marcarAtendida(id, responsable, ahora)
                .switchIfEmpty(Mono.error(() -> new AlertaNoEncontradaException(id)))
                .flatMap(atencion -> !atencion.realizada()
                        ? Mono.just(atencion.alerta())
                        : difusor.difundir(atencion.alerta())
                                .doOnSuccess(v -> log.info("Alerta {} atendida por {}", id, responsable))
                                .onErrorResume(e -> Mono.empty())
                                .thenReturn(atencion.alerta()));
    }
}
