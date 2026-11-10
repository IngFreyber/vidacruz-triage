package com.vidacruz.alertas.infrastructure.teams;

import com.vidacruz.alertas.application.port.out.NotificadorExterno;
import com.vidacruz.alertas.domain.Alerta;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Envía la alerta a un canal de Microsoft Teams mediante un webhook de <b>Workflows</b> (Power Automate), con
 * una Adaptive Card. Los conectores de Office 365 ("Incoming Webhook") fueron retirados por Microsoft.
 *
 * <p>Resiliencia: timeout por intento, reintentos con backoff (Retry) y CircuitBreaker: si Teams está caído,
 * las alertas siguientes fallan en milisegundos sin retrasar al consumidor. La URL del webhook contiene un
 * token secreto: llega solo por variable de entorno y nunca se registra en logs.
 */
public class TeamsWebhookNotificador implements NotificadorExterno {

    private final WebClient webClient;
    private final URI webhook;
    private final Duration timeout;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public TeamsWebhookNotificador(WebClient.Builder builder, URI webhook, Duration timeout,
                                   CircuitBreaker circuitBreaker, Retry retry) {
        this.webClient = builder.build();
        this.webhook = webhook;
        this.timeout = timeout;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    @Override
    public String nombre() {
        return "teams(" + webhook.getHost() + ")";
    }

    @Override
    public Mono<Void> notificar(Alerta alerta) {
        return webClient.post()
                .uri(webhook)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(tarjeta(alerta))
                .retrieve()
                .toBodilessEntity()
                .timeout(timeout)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .then();
    }

    /**
     * Mensaje con una Adaptive Card 1.4: título por nivel, texto de la alerta y datos clave.
     */
    static Map<String, Object> tarjeta(Alerta a) {
        Map<String, Object> card = Map.of(
                "$schema", "http://adaptivecards.io/schemas/adaptive-card.json",
                "type", "AdaptiveCard",
                "version", "1.4",
                "body", List.of(
                        Map.of("type", "TextBlock", "size", "Large", "weight", "Bolder", "color", "Attention",
                                "wrap", true, "text", "Escalamiento de paciente " + a.nivel()),
                        Map.of("type", "TextBlock", "wrap", true, "text", a.mensaje()),
                        Map.of("type", "FactSet", "facts", List.of(
                                Map.of("title", "Paciente", "value", a.nombrePaciente()),
                                Map.of("title", "Nivel", "value", a.nivel()),
                                Map.of("title", "Espera", "value",
                                        a.minutosEspera() + " min (SLA " + a.slaMinutos() + " min)"),
                                Map.of("title", "Motivo", "value", a.quejaPrincipal().isBlank() ? "—" : a.quejaPrincipal()),
                                Map.of("title", "Escalado", "value", a.escaladoEn().toString()),
                                Map.of("title", "Alerta", "value", a.id().toString())))));
        return Map.of(
                "type", "message",
                "attachments", List.of(Map.of(
                        "contentType", "application/vnd.microsoft.card.adaptive",
                        "content", card)));
    }
}
