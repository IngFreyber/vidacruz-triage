package com.vidacruz.triage.infrastructure.config;

import com.vidacruz.triage.application.port.out.MetricasTriage;
import com.vidacruz.triage.application.port.out.PacienteRepository;
import com.vidacruz.triage.application.port.out.ReporteOperacionRepository;
import com.vidacruz.triage.application.reporte.ReporteOperacionService;
import com.vidacruz.triage.application.service.EscalarPacientesService;
import com.vidacruz.triage.application.service.RegistroDeEventos;
import com.vidacruz.triage.domain.service.ClasificadorTriage;
import com.vidacruz.triage.domain.service.PoliticaSla;
import com.vidacruz.triage.domain.service.QuejasAdministrativas;
import com.vidacruz.triage.domain.service.ValidadorRegistro;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.reactive.TransactionalOperator;

import org.springframework.beans.factory.annotation.Value;

import java.time.Clock;
import java.time.Duration;

/**
 * Ensambla los servicios de dominio (Java puro) como beans. El dominio no conoce Spring.
 */
@Configuration
@EnableConfigurationProperties({TriageProperties.class, EscalamientoProperties.class})
public class DominioConfig {

    @Bean
    EscalarPacientesService escalarPacientesService(PacienteRepository pacientes, RegistroDeEventos eventos,
                                                    TransactionalOperator tx, EscalamientoProperties props,
                                                    MetricasTriage metricas) {
        return new EscalarPacientesService(pacientes, eventos, tx, props.tamanoLote(), metricas);
    }

    @Bean
    ReporteOperacionService reporteOperacionService(ReporteOperacionRepository repositorio,
                                                    @Value("${triage.reportes.rango-maximo:31d}") Duration rangoMaximo) {
        return new ReporteOperacionService(repositorio, rangoMaximo);
    }

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
