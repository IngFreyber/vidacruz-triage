package com.vidacruz.alertas.infrastructure.seguridad;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Autenticación por API key (ADR-0008).
 *
 * <ul>
 *   <li>Toda ruta exige la clave, salvo las probes ({@code /actuator/health/**}), {@code /actuator/info} y
 *       {@code /actuator/prometheus} (expuestas solo dentro del clúster, nunca por el ingress).</li>
 *   <li>Comparación en tiempo constante ({@link MessageDigest#isEqual}) para no filtrar información por tiempos.</li>
 *   <li>Sin sesión ni CSRF: API stateless consumida por máquinas.</li>
 *   <li>El principal es una huella (SHA-256 truncado) de la clave: identifica al cliente en logs sin exponerla.</li>
 *   <li>401/403 en formato RFC 7807, igual que el resto de errores.</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(SeguridadProperties.class)
public class SeguridadConfig {

    private static final Logger log = LoggerFactory.getLogger(SeguridadConfig.class);
    static final String PARAMETRO_QUERY = "api_key";

    @Bean
    SecurityWebFilterChain cadenaSeguridad(ServerHttpSecurity http, SeguridadProperties props, ObjectMapper mapper) {
        AuthenticationWebFilter filtroApiKey = new AuthenticationWebFilter(gestorApiKey(props));
        filtroApiKey.setServerAuthenticationConverter(convertidor(props));
        filtroApiKey.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        filtroApiKey.setAuthenticationFailureHandler(new ServerAuthenticationEntryPointFailureHandler(
                (exchange, ex) -> problema(exchange, mapper, HttpStatus.UNAUTHORIZED, "no-autenticado",
                        "No autenticado", "API key inválida")));

        log.info("Seguridad por API key activa: {} clave(s) configurada(s), cabecera {}", props.apiKeys().size(),
                props.header());

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(rutas -> rutas
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        .anyExchange().authenticated())
                .addFilterAt(filtroApiKey, SecurityWebFiltersOrder.AUTHENTICATION)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((exchange, ex) -> problema(exchange, mapper, HttpStatus.UNAUTHORIZED,
                                "no-autenticado", "No autenticado",
                                "Falta la API key. Envíela en la cabecera " + props.header() + "."))
                        .accessDeniedHandler((exchange, ex) -> problema(exchange, mapper, HttpStatus.FORBIDDEN,
                                "acceso-denegado", "Acceso denegado", "La credencial no tiene permiso para esta operación.")))
                .build();
    }

    private static ServerAuthenticationConverter convertidor(SeguridadProperties props) {
        return exchange -> {
            String clave = exchange.getRequest().getHeaders().getFirst(props.header());
            if ((clave == null || clave.isBlank())
                    && props.rutasConKeyEnQuery().contains(exchange.getRequest().getPath().value())) {
                clave = exchange.getRequest().getQueryParams().getFirst(PARAMETRO_QUERY);
            }
            return clave == null || clave.isBlank()
                    ? Mono.empty()
                    : Mono.just(new UsernamePasswordAuthenticationToken(null, clave.strip()));
        };
    }

    private static ReactiveAuthenticationManager gestorApiKey(SeguridadProperties props) {
        List<byte[]> validas = props.apiKeys().stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toList();
        return autenticacion -> {
            byte[] recibida = String.valueOf(autenticacion.getCredentials()).getBytes(StandardCharsets.UTF_8);
            boolean ok = false;
            for (byte[] valida : validas) {
                // Se recorren todas para que el tiempo no dependa de cuál coincide.
                ok |= MessageDigest.isEqual(valida, recibida);
            }
            return ok
                    ? Mono.just(new UsernamePasswordAuthenticationToken("cliente-" + huella(recibida), null,
                    AuthorityUtils.createAuthorityList("ROLE_API")))
                    : Mono.error(new BadCredentialsException("API key inválida"));
        };
    }

    static String huella(byte[] clave) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(clave)).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Mono<Void> problema(ServerWebExchange exchange, ObjectMapper mapper, HttpStatus status,
                                       String tipo, String titulo, String detalle) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detalle);
        pd.setType(URI.create("urn:vidacruz:problema:" + tipo));
        pd.setTitle(titulo);
        pd.setInstance(URI.create(exchange.getRequest().getPath().value()));
        pd.setProperty("timestamp", Instant.now().toString());
        var respuesta = exchange.getResponse();
        respuesta.setStatusCode(status);
        respuesta.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == HttpStatus.UNAUTHORIZED) {
            respuesta.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "ApiKey header=\"X-API-Key\"");
        }
        return Mono.fromCallable(() -> mapper.writeValueAsBytes(pd))
                .flatMap(bytes -> {
                    DataBuffer buffer = respuesta.bufferFactory().wrap(bytes);
                    return respuesta.writeWith(Mono.just(buffer));
                });
    }
}
