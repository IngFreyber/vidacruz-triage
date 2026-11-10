package com.vidacruz.alertas.infrastructure.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Carga scripts Lua <b>una sola vez, al arrancar</b>.
 *
 * <p>Detectado con BlockHound: {@code RedisScript.of(Resource)} lee el archivo de forma perezosa. Cuando Redis
 * aún no tiene el script en caché ({@code EVALSHA} → {@code NOSCRIPT}), el ejecutor reactivo recurre a
 * {@code EVAL} y lee el recurso del classpath <i>en el event loop de Lettuce</i>: una lectura de disco bloqueante
 * en medio del pipeline. Con el texto ya en memoria, el reintento es puramente no bloqueante.
 */
final class ScriptsLua {

    private ScriptsLua() {
    }

    static RedisScript<Long> cargar(String ruta) {
        try {
            return RedisScript.of(new ClassPathResource(ruta).getContentAsString(StandardCharsets.UTF_8), Long.class);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el script Lua " + ruta, e);
        }
    }
}
