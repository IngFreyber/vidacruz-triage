package com.vidacruz.alertas.infrastructure.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;

/**
 * Un único contenedor de escucha Pub/Sub por instancia, con el ciclo de vida de la aplicación. Las suscripciones
 * SSE entran y salen sin abrir ni cerrar conexiones: cancelar un {@code receive(...)} solo da de baja el canal.
 */
@Configuration(proxyBeanMethods = false)
public class ContenedorPubSubConfig {

    @Bean(destroyMethod = "destroy")
    ReactiveRedisMessageListenerContainer contenedorPubSub(ReactiveRedisConnectionFactory conexiones) {
        return new ReactiveRedisMessageListenerContainer(conexiones);
    }
}
