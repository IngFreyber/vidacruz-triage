package com.vidacruz.triage.domain.model;

/**
 * Datos de entrada sin validar para registrar un paciente (RF-01).
 * Los campos son nulables a propósito: la validación reporta los faltantes con un mensaje claro.
 */
public record SolicitudRegistro(
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

    public SignosVitales signosVitales() {
        return new SignosVitales(frecuenciaCardiaca, presionSistolica, presionDiastolica,
                spo2, temperatura, dolor, gcs);
    }
}
