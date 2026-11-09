package com.vidacruz.triage.infrastructure.web.dto;

import com.vidacruz.triage.domain.model.SolicitudRegistro;

/**
 * Cuerpo de {@code POST /triage/pacientes}. Los rangos se validan en el dominio
 * ({@link com.vidacruz.triage.domain.service.ValidadorRegistro}), única fuente de verdad.
 */
public record RegistroPacienteRequest(
        String nombreCompleto,
        Integer edad,
        Integer frecuenciaCardiaca,
        Integer presionSistolica,
        Integer presionDiastolica,
        Integer spo2,
        Double temperatura,
        Integer dolor,
        Integer gcs,
        String quejaPrincipal) {

    public SolicitudRegistro aSolicitud() {
        return new SolicitudRegistro(nombreCompleto, edad, frecuenciaCardiaca, presionSistolica, presionDiastolica,
                spo2, temperatura, dolor, gcs, quejaPrincipal);
    }
}
