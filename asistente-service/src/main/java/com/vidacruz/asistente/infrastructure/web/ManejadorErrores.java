package com.vidacruz.asistente.infrastructure.web;

import com.vidacruz.asistente.domain.consulta.LlmNoDisponibleException;
import com.vidacruz.asistente.domain.consulta.PreguntaInvalidaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
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

    /**
     * Modo degradado del asistente: el proveedor de LLM falló, excedió el tiempo o el circuito está abierto.
     */
    @ExceptionHandler(LlmNoDisponibleException.class)
    ResponseEntity<ProblemDetail> llmNoDisponible(LlmNoDisponibleException ex, ServerWebExchange exchange) {
        ProblemDetail pd = problema(HttpStatus.SERVICE_UNAVAILABLE, "asistente-no-disponible",
                "Asistente clínico no disponible temporalmente",
                "El servicio de IA no está disponible en este momento (" + ex.getMessage() + "). "
                        + "Consulte directamente el protocolo impreso o reintente en unos minutos. "
                        + "El triaje y la vista de urgencias siguen operando con normalidad.", exchange);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .body(pd);
    }

    @ExceptionHandler(PreguntaInvalidaException.class)
    ResponseEntity<ProblemDetail> preguntaInvalida(PreguntaInvalidaException ex, ServerWebExchange exchange) {
        ProblemDetail pd = problema(HttpStatus.BAD_REQUEST, "datos-invalidos", "Datos de entrada inválidos",
                "pregunta: " + ex.getMessage(), exchange);
        pd.setProperty("errores", List.of(Map.of("campo", "pregunta", "mensaje", ex.getMessage())));
        return respuesta(pd);
    }

    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<ProblemDetail> entradaInvalida(ServerWebInputException ex, ServerWebExchange exchange) {
        return respuesta(problema(HttpStatus.BAD_REQUEST, "peticion-invalida", "Petición inválida",
                "El cuerpo de la petición no es un JSON válido o falta", exchange));
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
