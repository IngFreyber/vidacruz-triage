package com.vidacruz.alertas.infrastructure.web;

import com.vidacruz.alertas.application.port.out.DifusorAlertas;
import com.vidacruz.alertas.application.service.GestionAlertasService;
import com.vidacruz.alertas.domain.Alerta;
import com.vidacruz.alertas.domain.EstadoAlerta;
import com.vidacruz.alertas.domain.SolicitudInvalidaException;
import com.vidacruz.alertas.infrastructure.config.AlertasProperties;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * RF-06: alertas de escalamiento al equipo médico.
 */
@RestController
@RequestMapping("/alertas")
public class AlertasController {

    static final String EVENTO_ALERTA = "alerta";
    static final String EVENTO_ATENDIDA = "alerta_atendida";
    private static final int BUFFER_POR_SUSCRIPTOR = 256;

    private final GestionAlertasService gestion;
    private final DifusorAlertas difusor;
    private final AlertasProperties props;

    public AlertasController(GestionAlertasService gestion, DifusorAlertas difusor, AlertasProperties props) {
        this.gestion = gestion;
        this.difusor = difusor;
        this.props = props;
    }

    /**
     * Historial, más recientes primero. {@code estado=PENDIENTE} lista lo que nadie ha tomado.
     */
    @GetMapping
    public Flux<Alerta> listar(@RequestParam(required = false) String estado,
                               @RequestParam(defaultValue = "50") int limite) {
        return Mono.fromCallable(() -> estado(estado))
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMapMany(e -> gestion.listar(e.orElse(null), limite));
    }

    @GetMapping("/{id}")
    public Mono<Alerta> obtener(@PathVariable UUID id) {
        return gestion.buscar(id);
    }

    /**
     * Confirmación de atención (acknowledge). Idempotente: queda registrado quién la tomó primero.
     */
    @PostMapping("/{id}/atencion")
    public Mono<Alerta> atender(@PathVariable UUID id, @RequestBody AtencionRequest request) {
        return gestion.atender(id, request.atendidaPor());
    }

    /**
     * Stream SSE para tableros del equipo médico. Al conectar se envían las alertas pendientes y luego, en
     * tiempo real, cada alerta nueva ({@code event: alerta}) o atendida ({@code event: alerta_atendida}).
     * Un comentario keep-alive periódico evita que proxies y balanceadores cierren la conexión inactiva.
     * Cada suscriptor tiene un buffer acotado: un cliente lento pierde los eventos más antiguos en lugar de
     * frenar a los demás (backpressure).
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Alerta>> stream(@RequestParam(defaultValue = "true") boolean pendientes) {
        Flux<Alerta> iniciales = pendientes ? gestion.listar(EstadoAlerta.PENDIENTE, 50) : Flux.empty();
        Flux<ServerSentEvent<Alerta>> alertas = Flux.merge(difusor.suscribir(), iniciales)
                .map(AlertasController::evento)
                .onBackpressureBuffer(BUFFER_POR_SUSCRIPTOR, BufferOverflowStrategy.DROP_OLDEST);
        Flux<ServerSentEvent<Alerta>> keepAlive = Flux.interval(props.heartbeat())
                .map(i -> ServerSentEvent.<Alerta>builder().comment("keep-alive").build());
        return Flux.merge(alertas, keepAlive);
    }

    private static ServerSentEvent<Alerta> evento(Alerta a) {
        return ServerSentEvent.<Alerta>builder()
                .id(a.id().toString())
                .event(a.pendiente() ? EVENTO_ALERTA : EVENTO_ATENDIDA)
                .data(a)
                .build();
    }

    private static EstadoAlerta estado(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return EstadoAlerta.valueOf(valor.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SolicitudInvalidaException("estado", "valor '%s' no reconocido; valores permitidos: %s"
                    .formatted(valor, Arrays.toString(EstadoAlerta.values())));
        }
    }

    /**
     * @param atendidaPor nombre o identificador del profesional que toma la alerta
     */
    public record AtencionRequest(String atendidaPor) {
    }
}
