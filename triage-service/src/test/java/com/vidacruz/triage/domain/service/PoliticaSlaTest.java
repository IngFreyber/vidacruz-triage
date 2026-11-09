package com.vidacruz.triage.domain.service;

import com.vidacruz.triage.domain.model.NivelTriage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PoliticaSlaTest {

    @ParameterizedTest(name = "{0} → {1} min")
    @CsvSource({"CRITICO, 5", "URGENTE, 10", "URGENTE_2, 30", "NO_URGENTE, 60", "NO_URGENTE_2, 120"})
    void slaPorDefectoSegunRn02(NivelTriage nivel, long minutos) {
        assertThat(PoliticaSla.porDefecto().slaDe(nivel)).isEqualTo(Duration.ofMinutes(minutos));
    }

    @Test
    void rechazaConfiguracionIncompleta() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PoliticaSla(Map.of(NivelTriage.CRITICO, Duration.ofMinutes(5))))
                .withMessageContaining("URGENTE");
    }
}
