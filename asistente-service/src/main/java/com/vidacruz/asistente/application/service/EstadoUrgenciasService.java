package com.vidacruz.asistente.application.service;

import com.vidacruz.asistente.application.port.out.VistaUrgenciasRepository;
import com.vidacruz.asistente.domain.urgencias.EstadoUrgencias;
import com.vidacruz.asistente.domain.urgencias.EventoTriaje;
import com.vidacruz.asistente.domain.urgencias.ResultadoAplicacion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * RF-04 parte B: mantiene y consulta el estado de la urgencia (pacientes activos por nivel). El estado se
 * reconstruye solo desde {@code triaje.eventos}; este servicio nunca llama a la API de triaje.
 */
@Service
public class EstadoUrgenciasService {

    private static final Logger log = LoggerFactory.getLogger(EstadoUrgenciasService.class);

    private final VistaUrgenciasRepository vista;

    public EstadoUrgenciasService(VistaUrgenciasRepository vista) {
        this.vista = vista;
    }

    public Mono<ResultadoAplicacion> aplicar(EventoTriaje evento) {
        return vista.aplicar(evento)
                .doOnNext(r -> {
                    if (r == ResultadoAplicacion.APLICADO) {
                        log.debug("Vista de urgencias: {} paciente={} nivel={} estado={}",
                                evento.tipo(), evento.pacienteId(), evento.nivel(), evento.estadoNuevo());
                    } else {
                        log.info("Evento {} ({}) ignorado por la vista: {}", evento.eventId(), evento.tipo(), r);
                    }
                });
    }

    public Mono<EstadoUrgencias> consultar() {
        return vista.consultar();
    }
}
