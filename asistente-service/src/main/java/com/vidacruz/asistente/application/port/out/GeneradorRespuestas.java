package com.vidacruz.asistente.application.port.out;

import com.vidacruz.asistente.domain.consulta.Fragmento;
import com.vidacruz.asistente.domain.consulta.RespuestaGenerada;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Generación con LLM restringida a los fragmentos entregados.
 * Emite {@link com.vidacruz.asistente.domain.consulta.LlmNoDisponibleException} si el proveedor no responde.
 */
public interface GeneradorRespuestas {

    Mono<RespuestaGenerada> generar(String pregunta, List<Fragmento> fragmentos);
}
