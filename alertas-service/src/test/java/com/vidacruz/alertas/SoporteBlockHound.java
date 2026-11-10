package com.vidacruz.alertas;

import reactor.blockhound.BlockHound;

/**
 * Instala BlockHound para toda la JVM de tests: cualquier llamada bloqueante ejecutada en un hilo no bloqueante
 * (event loop de Netty, schedulers {@code parallel}/{@code single} de Reactor) lanza {@code BlockingOperationError}
 * y hace fallar el test. Así cada test de integración verifica además que el código es realmente reactivo.
 *
 * <p>Excepciones permitidas (justificadas): escritura de logs a consola (Logback), generación de UUID (lectura de
 * entropía del SO, acotada y no evitable) y el lock de escritura de Lettuce (sección crítica en memoria).
 */
public final class SoporteBlockHound {

    private static volatile boolean instalado;

    private SoporteBlockHound() {
    }

    public static synchronized void instalar() {
        if (instalado) {
            return;
        }
        // install(...) carga las integraciones (ServiceLoader), entre ellas la de Reactor que marca los hilos
        // no bloqueantes; BlockHound.builder() sin ellas no detectaría nada.
        BlockHound.install(b -> b
                .allowBlockingCallsInside("ch.qos.logback.core.OutputStreamAppender", "subAppend")
                .allowBlockingCallsInside("ch.qos.logback.core.OutputStreamAppender", "writeBytes")
                .allowBlockingCallsInside("java.util.UUID", "randomUUID")
                // Lettuce >= 6.4 protege cada escritura en la conexión con un ReentrantLock de sección crítica
                // mínima (sin E/S). Bajo concurrencia un hilo espera microsegundos: no es una llamada bloqueante.
                .allowBlockingCallsInside("io.lettuce.core.protocol.SharedLock", "incrementWriters")
                .allowBlockingCallsInside("io.lettuce.core.protocol.SharedLock", "decrementWriters")
                .allowBlockingCallsInside("io.lettuce.core.protocol.SharedLock", "doExclusive"));
        instalado = true;
    }
}
