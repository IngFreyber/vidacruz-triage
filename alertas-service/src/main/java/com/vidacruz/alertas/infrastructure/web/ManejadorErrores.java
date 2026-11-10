package com.vidacruz.alertas.infrastructure.web;

import com.vidacruz.alertas.domain.AlertaNoEncontradaException;
import com.vidacruz.alertas.domain.SolicitudInvalidaException;
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
import org.springframework.web.server.ServerWebInputException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Errores en formato RFC 7807.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);
    private static final String TIPO_BASE = "urn:vidacruz:alertas:problema:";

    @ExceptionHandler(AlertaNoEncontradaException.class)
    ResponseEntity<ProblemDetail> noEncontrada(AlertaNoEncontradaException ex, ServerWebExchange exchange) {
        return respuesta(problema(HttpStatus.NOT_FOUND, "alerta-no-encontrada", "Alerta no encontrada",
                ex.getMessage(), exchange));
    }

    @ExceptionHandler(SolicitudInvalidaException.class)
    ResponseEntity<ProblemDetail> invalida(SolicitudInvalidaException ex, ServerWebExchange exchange) {
        ProblemDetail pd = problema(HttpStatus.BAD_REQUEST, "datos-invalidos", "Datos de entrada inválidos",
                ex.campo() + " " + ex.getMessage(), exchange);
        pd.setProperty("errores", List.of(Map.of("campo", ex.campo(), "mensaje", ex.getMessage())));
        return respuesta(pd);
    }

    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<ProblemDetail> entrada(ServerWebInputException ex, ServerWebExchange exchange) {
        String detalle = ex.getMethodParameter() != null && ex.getMethodParameter().getParameterName() != null
                ? "Parámetro '%s' inválido".formatted(ex.getMethodParameter().getParameterName())
                : "El cuerpo de la petición no es un JSON válido o falta";
        return respuesta(problema(HttpStatus.BAD_REQUEST, "peticion-invalida", "Petición inválida", detalle, exchange));
    }

    @ExceptionHandler({RedisConnectionFailureException.class, DataAccessResourceFailureException.class})
    ResponseEntity<ProblemDetail> noDisponible(Exception ex, ServerWebExchange exchange) {
        log.warn("Historial de alertas no disponible: {}", ex.toString());
        return respuesta(problema(HttpStatus.SERVICE_UNAVAILABLE, "historial-no-disponible",
                "Historial de alertas no disponible",
                "El almacén de alertas no responde. Reintente en unos segundos.", exchange));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> inesperado(Exception ex, ServerWebExchange exchange) {
        if (ex instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.valueOf(rse.getStatusCode().value());
            return respuesta(problema(status, "http-" + status.value(), status.getReasonPhrase(),
                    rse.getReason() != null ? rse.getReason() : status.getReasonPhrase(), exchange));
        }
        log.error("Error no controlado en {} {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(), ex);
        return respuesta(problema(HttpStatus.INTERNAL_SERVER_ERROR, "error-interno", "Error interno",
                "Ocurrió un error inesperado. Consulte los logs con la hora de la petición.", exchange));
    }

    private static ProblemDetail problema(HttpStatus status, String tipo, String titulo, String detalle,
                                          ServerWebExchange exchange) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detalle);
        pd.setType(URI.create(TIPO_BASE + tipo));
        pd.setTitle(titulo);
        pd.setInstance(URI.create(exchange.getRequest().getPath().value()));
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    private static ResponseEntity<ProblemDetail> respuesta(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus()).body(pd);
    }
}
