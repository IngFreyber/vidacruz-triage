package com.vidacruz.triage.domain.service;

import com.vidacruz.triage.domain.model.NivelTriage;

import java.util.List;

/**
 * @param nivel     nivel asignado
 * @param criterios criterios clínicos que determinaron el nivel (trazabilidad de la decisión)
 */
public record ResultadoClasificacion(NivelTriage nivel, List<String> criterios) {

    public ResultadoClasificacion {
        criterios = List.copyOf(criterios);
    }
}
