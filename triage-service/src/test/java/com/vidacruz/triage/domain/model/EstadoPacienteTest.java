package com.vidacruz.triage.domain.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class EstadoPacienteTest {

    @ParameterizedTest(name = "{0} → {1}: {2}")
    @CsvSource({
            "REGISTRADO,  EN_CONSULTA, true",
            "REGISTRADO,  ALTA,        true",
            "EN_CONSULTA, ALTA,        true",
            "REGISTRADO,  REGISTRADO,  false",
            "EN_CONSULTA, REGISTRADO,  false",
            "EN_CONSULTA, EN_CONSULTA, false",
            "ALTA,        REGISTRADO,  false",
            "ALTA,        EN_CONSULTA, false",
            "ALTA,        ALTA,        false"})
    void matrizCompletaDeTransiciones(EstadoPaciente origen, EstadoPaciente destino, boolean permitida) {
        assertThat(origen.puedeTransicionarA(destino)).isEqualTo(permitida);
    }
}
