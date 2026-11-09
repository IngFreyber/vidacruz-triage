package com.vidacruz.triage.infrastructure.web;

import com.vidacruz.triage.application.reporte.ReporteOperacionService;
import com.vidacruz.triage.domain.exception.DatosPacienteInvalidosException.Violacion;
import com.vidacruz.triage.domain.exception.ParametrosInvalidosException;
import com.vidacruz.triage.infrastructure.web.dto.ReporteOperacionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * RF-05: reporte de operación agrupado por hora.
 */
@RestController
@RequestMapping("/triage/reportes")
public class ReporteController {

    private final ReporteOperacionService reportes;

    public ReporteController(ReporteOperacionService reportes) {
        this.reportes = reportes;
    }

    /**
     * @param desde inicio del rango, inclusivo (ISO 8601)
     * @param hasta fin del rango, exclusivo (ISO 8601)
     */
    @GetMapping("/operacion")
    public Mono<ReporteOperacionResponse> operacion(@RequestParam(required = false) String desde,
                                                    @RequestParam(required = false) String hasta) {
        return Mono.fromCallable(() -> parsearRango(desde, hasta))
                .flatMap(rango -> reportes.generar(rango[0], rango[1]))
                .map(ReporteOperacionResponse::desde);
    }

    /** Reporta ambos parámetros inválidos a la vez. */
    private static Instant[] parsearRango(String desde, String hasta) {
        List<Violacion> errores = new ArrayList<>();
        Instant inicio = parsear("desde", desde, errores);
        Instant fin = parsear("hasta", hasta, errores);
        if (!errores.isEmpty()) {
            throw new ParametrosInvalidosException(errores);
        }
        return new Instant[]{inicio, fin};
    }

    private static Instant parsear(String campo, String valor, List<Violacion> errores) {
        try {
            return FechaIso.parsear(campo, valor);
        } catch (ParametrosInvalidosException e) {
            errores.addAll(e.violaciones());
            return null;
        }
    }
}
