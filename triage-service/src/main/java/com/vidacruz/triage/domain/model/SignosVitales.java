package com.vidacruz.triage.domain.model;

/**
 * Signos vitales ya validados (ver {@link com.vidacruz.triage.domain.service.ValidadorRegistro}).
 *
 * @param frecuenciaCardiaca bpm
 * @param presionSistolica   mmHg
 * @param presionDiastolica  mmHg
 * @param spo2               %
 * @param temperatura        °C
 * @param dolor              escala 0-10
 * @param gcs                escala de Glasgow 3-15
 */
public record SignosVitales(
        int frecuenciaCardiaca,
        int presionSistolica,
        int presionDiastolica,
        int spo2,
        double temperatura,
        int dolor,
        int gcs) {
}
