package com.vidacruz.asistente.infrastructure.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
import java.time.Instant;

/**
 * Errores del asistente en formato RFC 7807. Una dependencia caída (Redis) es una indisponibilidad temporal
 * (503), no un error interno (500).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);
    private static final String TIPO_BASE = "urn:vidacruz:asistente:problema:";

    @ExceptionHandler({RedisConnectionFailureException.class, DataAccessResourceFailureException.class})
    ResponseEntity<ProblemDetail> vistaNoDisponible(Exception ex, ServerWebExchange exchange) {
        log.warn("Vista de urgencias no disponible: {}", ex.toString());
        return respuesta(problema(HttpStatus.SERVICE_UNAVAILABLE, "vista-no-disponible",
                "Estado de urgencias no disponible",
                "El almacén de la vista de urgencias no responde. Reintente en unos segundos; "
                        + "los eventos se siguen recibiendo y se aplicarán al recuperarse.", exchange));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> inesperado(Exception ex, ServerWebExchange exchange) {
        if (ex instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.valueOf(rse.getStatusCode().value());
            return respuesta(problema(status, "http-" + status.value(), status.getReasonPhrase(),
                    rse.getReason() != null ? rse.getReason() : status.getReasonPhrase(), exchange));
        }
        log.error("Error no controlado en {} {}", exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(), ex);
        return respuesta(problema(HttpStatus.INTERNAL_SERVER_ERROR, "error-interno", "Error interno",
                "Ocurrió un error inesperado. Consulte los logs con la hora de la petición.", exchange));
    }

    static ProblemDetail problema(HttpStatus status, String tipo, String titulo, String detalle,
                                  ServerWebExchange exchange) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detalle);
        pd.setType(URI.create(TIPO_BASE + tipo));
        pd.setTitle(titulo);
        pd.setInstance(URI.create(exchange.getRequest().getPath().value()));
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    static ResponseEntity<ProblemDetail> respuesta(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus()).body(pd);
    }
}
