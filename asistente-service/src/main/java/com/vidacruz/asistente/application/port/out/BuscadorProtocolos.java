package com.vidacruz.asistente.application.port.out;

import com.vidacruz.asistente.domain.consulta.Fragmento;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Recuperación semántica sobre el corpus oficial. Solo devuelve fragmentos que superan el umbral de
 * similitud: una lista vacía significa "no hay protocolo relevante".
 */
public interface BuscadorProtocolos {

    Mono<List<Fragmento>> buscar(String pregunta);
}
