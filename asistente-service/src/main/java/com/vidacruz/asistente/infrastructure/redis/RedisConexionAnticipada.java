package com.vidacruz.asistente.infrastructure.redis;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Inicializa la conexión compartida de Lettuce al arrancar.
 *
 * <p>Detectado con BlockHound: por defecto {@link LettuceConnectionFactory} abre la conexión compartida de forma
 * perezosa y <b>síncrona</b> ({@code RedisClient.connect()} → {@code CompletableFuture.get()}) la primera vez que
 * se usa, es decir, dentro de la primera petición reactiva y sobre un hilo no bloqueante. Con la inicialización
 * anticipada la conexión se establece en el hilo principal durante el arranque, antes de que el servicio esté
 * listo; las reconexiones posteriores de Lettuce son asíncronas.
 */
@Configuration(proxyBeanMethods = false)
public class RedisConexionAnticipada {

    @Bean
    static BeanPostProcessor conexionRedisAnticipada() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String nombre) {
                if (bean instanceof LettuceConnectionFactory fabrica) {
                    fabrica.setEagerInitialization(true);
                }
                return bean;
            }
        };
    }
}
