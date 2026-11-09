package com.vidacruz.triage.domain.model;

import com.vidacruz.triage.domain.exception.TransicionEstadoInvalidaException;
import com.vidacruz.triage.domain.service.ClasificadorTriage;
import com.vidacruz.triage.domain.service.PoliticaSla;
import com.vidacruz.triage.domain.service.ResultadoClasificacion;
import com.vidacruz.triage.domain.service.ValidadorRegistro;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Agregado raíz del triaje. Inmutable: cada operación devuelve una {@link Transicion} con el nuevo estado
 * del paciente y el {@link EventoPaciente} que la describe (fuente de la auditoría y del evento Kafka).
 *
 * @param version versión para control de concurrencia optimista en persistencia ({@code null} si es nuevo)
 */
public record Paciente(
        UUID id,
        String nombreCompleto,
        int edad,
        SignosVitales signosVitales,
        String quejaPrincipal,
        NivelTriage nivel,
        List<String> criteriosClasificacion,
        Duration sla,
        EstadoPaciente estado,
        boolean escalado,
        Instant fechaRegistro,
        Instant fechaCambioEstado,
        Instant fechaEscalamiento,
        Long version) {

    public Paciente {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nivel, "nivel");
        Objects.requireNonNull(sla, "sla");
        Objects.requireNonNull(estado, "estado");
        Objects.requireNonNull(fechaRegistro, "fechaRegistro");
        Objects.requireNonNull(fechaCambioEstado, "fechaCambioEstado");
        criteriosClasificacion = criteriosClasificacion == null ? List.of() : List.copyOf(criteriosClasificacion);
    }

    /**
     * RF-01: valida, clasifica (RN-01), asigna SLA (RN-02) y deja al paciente en {@code REGISTRADO}.
     */
    public static Transicion registrar(UUID id, SolicitudRegistro solicitud, ValidadorRegistro validador,
                                       ClasificadorTriage clasificador, PoliticaSla politicaSla, Instant ahora) {
        validador.validar(solicitud);
        SignosVitales signos = solicitud.signosVitales();
        ResultadoClasificacion clasificacion = clasificador.clasificar(signos, solicitud.quejaPrincipal());

        Paciente paciente = new Paciente(id, solicitud.nombreCompleto().strip(), solicitud.edad(), signos,
                solicitud.quejaPrincipal().strip(), clasificacion.nivel(), clasificacion.criterios(),
                politicaSla.slaDe(clasificacion.nivel()), EstadoPaciente.REGISTRADO, false,
                ahora, ahora, null, null);

        return new Transicion(paciente, paciente.evento(TipoEvento.PACIENTE_REGISTRADO, null,
                EstadoPaciente.REGISTRADO, "Clasificado como " + clasificacion.nivel() + ": "
                        + String.join(", ", clasificacion.criterios()), ahora));
    }

    /**
     * RF-02: aplica una transición de la máquina de estados.
     *
     * @throws TransicionEstadoInvalidaException si la transición no está permitida (HTTP 409)
     */
    public Transicion cambiarEstado(EstadoPaciente destino, Instant ahora) {
        Objects.requireNonNull(destino, "destino");
        if (!estado.puedeTransicionarA(destino)) {
            throw new TransicionEstadoInvalidaException(id, estado, destino);
        }
        Paciente nuevo = new Paciente(id, nombreCompleto, edad, signosVitales, quejaPrincipal, nivel,
                criteriosClasificacion, sla, destino, escalado, fechaRegistro, ahora, fechaEscalamiento, version);
        return new Transicion(nuevo, evento(TipoEvento.porTransicionA(destino), estado, destino,
                "Transición " + estado + " → " + destino, ahora));
    }

    /**
     * RF-03 / RN-03: escala al paciente si superó su SLA sin entrar a consulta.
     * Es idempotente: devuelve vacío si ya estaba escalado, si no está en {@code REGISTRADO}
     * o si el SLA aún no vence. Solo el primer escalamiento produce evento.
     */
    public Optional<Transicion> escalarSiVencido(Instant ahora) {
        if (!esEscalable(ahora)) {
            return Optional.empty();
        }
        Paciente nuevo = new Paciente(id, nombreCompleto, edad, signosVitales, quejaPrincipal, nivel,
                criteriosClasificacion, sla, estado, true, fechaRegistro, fechaCambioEstado, ahora, version);
        return Optional.of(new Transicion(nuevo, evento(TipoEvento.PACIENTE_ESCALADO, estado, estado,
                "Flag escalado false → true: SLA de %d min vencido (espera de %d min)"
                        .formatted(sla.toMinutes(), Duration.between(fechaRegistro, ahora).toMinutes()), ahora)));
    }

    public boolean esEscalable(Instant ahora) {
        return estado == EstadoPaciente.REGISTRADO && !escalado && slaVencido(ahora);
    }

    public boolean slaVencido(Instant ahora) {
        return ahora.isAfter(vencimientoSla());
    }

    public Instant vencimientoSla() {
        return fechaRegistro.plus(sla);
    }

    public Duration tiempoEnEstadoActual(Instant ahora) {
        return Duration.between(fechaCambioEstado, ahora);
    }

    private EventoPaciente evento(TipoEvento tipo, EstadoPaciente anterior, EstadoPaciente nuevo,
                                  String detalle, Instant ahora) {
        return new EventoPaciente(UUID.randomUUID(), tipo, id, nivel, anterior, nuevo, detalle, ahora);
    }
}
