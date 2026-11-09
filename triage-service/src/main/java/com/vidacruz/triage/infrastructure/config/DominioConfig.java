package com.vidacruz.triage.infrastructure.config;

import com.vidacruz.triage.domain.service.ClasificadorTriage;
import com.vidacruz.triage.domain.service.PoliticaSla;
import com.vidacruz.triage.domain.service.QuejasAdministrativas;
import com.vidacruz.triage.domain.service.ValidadorRegistro;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Ensambla los servicios de dominio (Java puro) como beans. El dominio no conoce Spring.
 */
@Configuration
@EnableConfigurationProperties(TriageProperties.class)
public class DominioConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ValidadorRegistro validadorRegistro() {
        return new ValidadorRegistro();
    }

    @Bean
    ClasificadorTriage clasificadorTriage(TriageProperties props) {
        QuejasAdministrativas quejas = props.quejasAdministrativas() == null || props.quejasAdministrativas().isEmpty()
                ? QuejasAdministrativas.porDefecto()
                : new QuejasAdministrativas(props.quejasAdministrativas());
        return new ClasificadorTriage(quejas);
    }

    @Bean
    PoliticaSla politicaSla(TriageProperties props) {
        return props.sla() == null || props.sla().isEmpty() ? PoliticaSla.porDefecto() : new PoliticaSla(props.sla());
    }
}
