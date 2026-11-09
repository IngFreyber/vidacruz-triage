package com.vidacruz.asistente.infrastructure.web;

import com.vidacruz.asistente.application.service.EstadoUrgenciasService;
import com.vidacruz.asistente.infrastructure.web.dto.EstadoUrgenciasResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * RF-04 parte B: estado de la urgencia en tiempo real (pacientes activos por nivel).
 */
@RestController
@RequestMapping("/asistente")
public class EstadoUrgenciasController {

    private final EstadoUrgenciasService urgencias;

    public EstadoUrgenciasController(EstadoUrgenciasService urgencias) {
        this.urgencias = urgencias;
    }

    @GetMapping("/estado-urgencias")
    public Mono<EstadoUrgenciasResponse> estado() {
        return urgencias.consultar().map(EstadoUrgenciasResponse::desde);
    }
}
