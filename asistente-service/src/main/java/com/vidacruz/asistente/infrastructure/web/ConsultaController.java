package com.vidacruz.asistente.infrastructure.web;

import com.vidacruz.asistente.application.service.ConsultaClinicaService;
import com.vidacruz.asistente.infrastructure.web.dto.ConsultaRequest;
import com.vidacruz.asistente.infrastructure.web.dto.ConsultaResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * RF-04 parte A: consulta de protocolos clínicos en lenguaje natural.
 */
@RestController
@RequestMapping("/asistente")
public class ConsultaController {

    private final ConsultaClinicaService consultas;
    private final MeterRegistry metricas;

    public ConsultaController(ConsultaClinicaService consultas, MeterRegistry metricas) {
        this.consultas = consultas;
        this.metricas = metricas;
    }

    @PostMapping("/consulta")
    public Mono<ConsultaResponse> consultar(@RequestBody ConsultaRequest request) {
        return consultas.consultar(request.pregunta())
                // asistente.consultas{estado}: proporción de respuestas citadas, sin protocolo y descartadas (RN-05).
                .doOnNext(r -> metricas.counter("asistente.consultas", "estado", r.estado().name()).increment())
                .map(ConsultaResponse::desde);
    }
}
