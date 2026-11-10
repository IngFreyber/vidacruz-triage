package com.vidacruz.triage.infrastructure.seguridad;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Configuración de la autenticación por API key.
 *
 * @param apiKeys             claves válidas (separadas por coma: permite rotar sin cortar el servicio). Solo
 *                            por variable de entorno; no hay valor por defecto. Mínimo 16 caracteres cada una
 * @param header              cabecera donde el cliente envía la clave
 * @param rutasConKeyEnQuery  rutas que además aceptan la clave como parámetro {@code api_key} (p. ej. SSE, porque
 *                            {@code EventSource} del navegador no permite cabeceras)
 */
@ConfigurationProperties(prefix = "seguridad")
public record SeguridadProperties(
        List<String> apiKeys,
        @DefaultValue("X-API-Key") String header,
        @DefaultValue List<String> rutasConKeyEnQuery) {

    static final int LARGO_MINIMO = 16;

    public SeguridadProperties {
        if (apiKeys == null || apiKeys.stream().allMatch(k -> k == null || k.isBlank())) {
            throw new IllegalStateException("No hay API keys configuradas (propiedad 'seguridad.api-keys'). "
                    + "Defina la variable de entorno correspondiente; no existen claves por defecto.");
        }
        apiKeys = apiKeys.stream().filter(k -> k != null && !k.isBlank()).map(String::strip).toList();
        if (apiKeys.stream().anyMatch(k -> k.length() < LARGO_MINIMO)) {
            throw new IllegalStateException("Cada API key debe tener al menos " + LARGO_MINIMO + " caracteres");
        }
        rutasConKeyEnQuery = rutasConKeyEnQuery == null ? List.of() : List.copyOf(rutasConKeyEnQuery);
    }
}
