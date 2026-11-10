package com.vidacruz.asistente.infrastructure.observabilidad;

import io.micrometer.tracing.handler.TracingObservationHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Devuelve el {@code traceId} de la petición en la cabecera {@code X-Trace-Id}. Un cliente que reporta un
 * problema puede citar ese id y encontrar en los logs (y en el trazado distribuido) todo lo que ocurrió,
 * incluido lo procesado después por los consumidores de Kafka.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdWebFilter implements WebFilter {

    public static final String CABECERA = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .map(c -> c.<TracingObservationHandler.TracingContext>get(TracingObservationHandler.TracingContext.class))
                .map(tc -> tc.getSpan())
                .filter(Objects::nonNull)
                .ifPresent(span -> exchange.getResponse().getHeaders().set(CABECERA, span.context().traceId()));
        return chain.filter(exchange);
    }
}
