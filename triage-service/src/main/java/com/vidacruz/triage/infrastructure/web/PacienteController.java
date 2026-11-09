package com.vidacruz.triage.infrastructure.web;

import com.vidacruz.triage.application.service.CambiarEstadoPacienteService;
import com.vidacruz.triage.application.service.ConsultarPacienteService;
import com.vidacruz.triage.application.service.RegistrarPacienteService;
import com.vidacruz.triage.application.service.Tiempo;
import com.vidacruz.triage.infrastructure.web.dto.AuditoriaResponse;
import com.vidacruz.triage.infrastructure.web.dto.CambioEstadoRequest;
import com.vidacruz.triage.infrastructure.web.dto.PacienteRegistradoResponse;
import com.vidacruz.triage.infrastructure.web.dto.PacienteResponse;
import com.vidacruz.triage.infrastructure.web.dto.RegistroPacienteRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/**
 * API de pacientes (RF-01, RF-02). No existe ningún endpoint de borrado (RN-04).
 */
@RestController
@RequestMapping("/triage/pacientes")
public class PacienteController {

    private final RegistrarPacienteService registrar;
    private final CambiarEstadoPacienteService cambiarEstado;
    private final ConsultarPacienteService consultar;
    private final Clock clock;

    public PacienteController(RegistrarPacienteService registrar, CambiarEstadoPacienteService cambiarEstado,
                              ConsultarPacienteService consultar, Clock clock) {
        this.registrar = registrar;
        this.cambiarEstado = cambiarEstado;
        this.consultar = consultar;
        this.clock = clock;
    }

    @PostMapping
    public Mono<ResponseEntity<PacienteRegistradoResponse>> registrar(@RequestBody RegistroPacienteRequest request,
                                                                      UriComponentsBuilder uri) {
        return registrar.registrar(request.aSolicitud())
                .map(p -> ResponseEntity
                        .created(uri.path("/triage/pacientes/{id}").buildAndExpand(p.id()).toUri())
                        .body(PacienteRegistradoResponse.desde(p)));
    }

    @GetMapping("/{id}")
    public Mono<PacienteResponse> obtener(@PathVariable UUID id) {
        return consultar.buscar(id).map(p -> PacienteResponse.desde(p, Tiempo.ahora(clock)));
    }

    @PatchMapping("/{id}/estado")
    public Mono<PacienteResponse> cambiarEstado(@PathVariable UUID id, @RequestBody CambioEstadoRequest request) {
        return Mono.fromCallable(request::estadoDestino)
                .flatMap(destino -> cambiarEstado.cambiarEstado(id, destino))
                .map(p -> PacienteResponse.desde(p, Tiempo.ahora(clock)));
    }

    @GetMapping("/{id}/auditoria")
    public Flux<AuditoriaResponse> auditoria(@PathVariable UUID id) {
        return consultar.auditoria(id).map(AuditoriaResponse::desde);
    }
}
