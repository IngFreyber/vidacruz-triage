package com.vidacruz.triage.infrastructure.observabilidad;

import com.vidacruz.triage.application.port.out.MetricasTriage;
import com.vidacruz.triage.domain.model.NivelTriage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Contadores Micrometer (expuestos en {@code /actuator/prometheus} como {@code triage_clasificacion_total} y
 * {@code triage_escalamientos_total}). Se registran todos los niveles al arrancar, así una serie en 0 es visible
 * desde el inicio y no "aparece" con el primer paciente.
 */
@Component
public class MicrometerMetricasTriage implements MetricasTriage {

    private final Map<NivelTriage, Counter> clasificaciones = new EnumMap<>(NivelTriage.class);
    private final Map<NivelTriage, Counter> escalamientos = new EnumMap<>(NivelTriage.class);

    public MicrometerMetricasTriage(MeterRegistry registry) {
        for (NivelTriage nivel : NivelTriage.values()) {
            clasificaciones.put(nivel, Counter.builder("triage.clasificacion")
                    .description("Pacientes clasificados por nivel de triaje")
                    .tag("nivel", nivel.name())
                    .register(registry));
            escalamientos.put(nivel, Counter.builder("triage.escalamientos")
                    .description("Pacientes escalados por superar su SLA sin entrar a consulta")
                    .tag("nivel", nivel.name())
                    .register(registry));
        }
    }

    @Override
    public void pacienteClasificado(NivelTriage nivel) {
        clasificaciones.get(nivel).increment();
    }

    @Override
    public void pacienteEscalado(NivelTriage nivel) {
        escalamientos.get(nivel).increment();
    }
}
