package com.vidacruz.triage.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.vidacruz.triage.domain.exception.ConflictoConcurrenciaException;
import com.vidacruz.triage.domain.exception.DatosPacienteInvalidosException;
import com.vidacruz.triage.domain.exception.DatosPacienteInvalidosException.Violacion;
import com.vidacruz.triage.domain.exception.ParametrosInvalidosException;
import com.vidacruz.triage.domain.exception.PacienteNoEncontradoException;
import com.vidacruz.triage.domain.exception.TransicionEstadoInvalidaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Traduce las excepciones a respuestas RFC 7807 ({@code application/problem+json}) con un formato uniforme:
 * {@code type, title, status, detail, instance, timestamp} y, en errores de validación, {@code errores[]}.
 * Nunca expone trazas ni detalles internos.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);
    private static final String TIPO_BASE = "urn:vidacruz:triage:problema:";

    @ExceptionHandler(DatosPacienteInvalidosException.class)
    ResponseEntity<ProblemDetail> datosInvalidos(DatosPacienteInvalidosException ex, ServerWebExchange exchange) {
        return validacion(ex.violaciones(), exchange);
    }

    @ExceptionHandler(ParametrosInvalidosException.class)
    ResponseEntity<ProblemDetail> parametrosInvalidos(ParametrosInvalidosException ex, ServerWebExchange exchange) {
        return validacion(ex.violaciones(), exchange);
    }

    private static ResponseEntity<ProblemDetail> validacion(List<Violacion> violaciones, ServerWebExchange exchange) {
        List<Map<String, String>> errores = violaciones.stream()
                .map(v -> Map.of("campo", v.campo(), "mensaje", v.mensaje()))
                .toList();
        ProblemDetail pd = problema(HttpStatus.BAD_REQUEST, "datos-invalidos", "Datos de entrada inválidos",
                violaciones.stream().map(v -> v.campo() + " " + v.mensaje())
                        .collect(Collectors.joining("; ")), exchange);
        pd.setProperty("errores", errores);
        return respuesta(pd);
    }

    @ExceptionHandler(TransicionEstadoInvalidaException.class)
    ResponseEntity<ProblemDetail> transicionInvalida(TransicionEstadoInvalidaException ex, ServerWebExchange exchange) {
        ProblemDetail pd = problema(HttpStatus.CONFLICT, "transicion-invalida", "Transición de estado inválida",
                ex.getMessage(), exchange);
        pd.setProperty("estadoActual", ex.actual());
        pd.setProperty("estadoSolicitado", ex.destino());
        pd.setProperty("transicionesPermitidas", ex.actual().transicionesPermitidas());
        return respuesta(pd);
    }

    @ExceptionHandler(ConflictoConcurrenciaException.class)
    ResponseEntity<ProblemDetail> conflicto(ConflictoConcurrenciaException ex, ServerWebExchange exchange) {
        return respuesta(problema(HttpStatus.CONFLICT, "conflicto-concurrencia", "Modificación concurrente",
                ex.getMessage(), exchange));
    }

    @ExceptionHandler(PacienteNoEncontradoException.class)
    ResponseEntity<ProblemDetail> noEncontrado(PacienteNoEncontradoException ex, ServerWebExchange exchange) {
        return respuesta(problema(HttpStatus.NOT_FOUND, "paciente-no-encontrado", "Paciente no encontrado",
                ex.getMessage(), exchange));
    }

    /**
     * Cuerpo ausente, JSON mal formado, tipos incorrectos o parámetros de ruta inválidos (p. ej. UUID).
     */
    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<ProblemDetail> entradaInvalida(ServerWebInputException ex, ServerWebExchange exchange) {
        Throwable causa = NestedExceptionUtils.getMostSpecificCause(ex);
        String detalle;
        if (causa instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
            String campo = jme.getPath().stream()
                    .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                    .collect(Collectors.joining("."));
            ProblemDetail pd = problema(HttpStatus.BAD_REQUEST, "datos-invalidos", "Datos de entrada inválidos",
                    campo + " tiene un tipo o formato inválido", exchange);
            pd.setProperty("errores", List.of(Map.of("campo", campo, "mensaje", "tipo o formato inválido")));
            return respuesta(pd);
        } else if (causa instanceof JsonProcessingException) {
            detalle = "El cuerpo de la petición no es un JSON válido";
        } else if (ex.getMethodParameter() != null && ex.getMethodParameter().getParameterName() != null) {
            detalle = "Parámetro '%s' inválido".formatted(ex.getMethodParameter().getParameterName());
        } else {
            detalle = ex.getReason() != null ? ex.getReason() : "Petición inválida";
        }
        return respuesta(problema(HttpStatus.BAD_REQUEST, "peticion-invalida", "Petición inválida", detalle, exchange));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> inesperado(Exception ex, ServerWebExchange exchange) {
        if (ex instanceof org.springframework.web.server.ResponseStatusException rse) {
            HttpStatus status = HttpStatus.valueOf(rse.getStatusCode().value());
            return respuesta(problema(status, "http-" + status.value(), status.getReasonPhrase(),
                    rse.getReason() != null ? rse.getReason() : status.getReasonPhrase(), exchange));
        }
        log.error("Error no controlado en {} {}", exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(), ex);
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
