package com.vidacruz.alertas.infrastructure.config;

import com.vidacruz.alertas.application.port.out.NotificadorExterno;
import com.vidacruz.alertas.infrastructure.teams.TeamsWebhookNotificador;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Clock;
import java.util.List;

@Configuration
@EnableConfigurationProperties(AlertasProperties.class)
public class AlertasConfig {

    private static final Logger log = LoggerFactory.getLogger(AlertasConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Canales externos activos. Teams solo se agrega si hay URL configurada (TEAMS_WEBHOOK_URL); sin ella, las
     * alertas siguen llegando por SSE y quedan en el historial.
     */
    @Bean
    List<NotificadorExterno> notificadoresExternos(AlertasProperties props, WebClient.Builder webClient,
                                                   CircuitBreakerRegistry circuitBreakers, RetryRegistry retries) {
        if (!props.teams().habilitado()) {
            log.info("Canal Microsoft Teams deshabilitado (TEAMS_WEBHOOK_URL vacía); alertas solo por SSE e historial");
            return List.of();
        }
        Retry retry = retries.retry("teams");
        TeamsWebhookNotificador teams = new TeamsWebhookNotificador(webClient, URI.create(props.teams().webhookUrl()),
                props.teams().timeout(), circuitBreakers.circuitBreaker("teams"), retry);
        log.info("Canal Microsoft Teams habilitado: {}", teams.nombre());
        return List.of(teams);
    }
}
