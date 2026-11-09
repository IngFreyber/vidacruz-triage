package com.vidacruz.asistente.infrastructure.web;

import com.vidacruz.asistente.application.service.ConsultaClinicaService;
import com.vidacruz.asistente.infrastructure.web.dto.ConsultaRequest;
import com.vidacruz.asistente.infrastructure.web.dto.ConsultaResponse;
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

    public ConsultaController(ConsultaClinicaService consultas) {
        this.consultas = consultas;
    }

    @PostMapping("/consulta")
    public Mono<ConsultaResponse> consultar(@RequestBody ConsultaRequest request) {
        return consultas.consultar(request.pregunta()).map(ConsultaResponse::desde);
    }
}
