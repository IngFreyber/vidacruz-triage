package com.vidacruz.asistente.infrastructure.observabilidad;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.observation.transport.ReceiverContext;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;

import java.nio.charset.StandardCharsets;

/**
 * Instrumenta el procesamiento de un mensaje Kafka como una observación de tipo <i>consumer</i>.
 *
 * <ul>
 *   <li><b>Trazado distribuido</b>: extrae el {@code traceparent} W3C que publica triage-service, así el span
 *       del consumo es hijo del request HTTP que originó el evento (una sola traza de punta a punta).</li>
 *   <li><b>Logs</b>: la observación se coloca en el contexto de Reactor; con context-propagation, cada log del
 *       procesamiento lleva el mismo {@code traceId}.</li>
 *   <li><b>Métrica</b>: Micrometer registra además un timer con la latencia de procesamiento por mensaje.</li>
 * </ul>
 */
public final class ObservacionKafka {

    private ObservacionKafka() {
    }

    public static <T> Mono<T> observar(ObservationRegistry registry, String nombre, ReceiverRecord<?, ?> registro,
                                       Mono<T> procesamiento) {
        ReceiverContext<Headers> contexto = new ReceiverContext<>(ObservacionKafka::leerHeader);
        contexto.setCarrier(registro.headers());
        contexto.setRemoteServiceName("kafka");
        Observation observacion = Observation.createNotStarted(nombre, () -> contexto, registry)
                .contextualName("consumir " + registro.topic())
                .lowCardinalityKeyValue("messaging.system", "kafka")
                .lowCardinalityKeyValue("messaging.destination", registro.topic())
                .highCardinalityKeyValue("messaging.kafka.partition", String.valueOf(registro.partition()))
                .highCardinalityKeyValue("messaging.kafka.offset", String.valueOf(registro.offset()))
                .start();
        return procesamiento
                .doOnError(observacion::error)
                .doFinally(senal -> observacion.stop())
                .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observacion));
    }

    private static String leerHeader(Headers headers, String clave) {
        Header header = headers.lastHeader(clave);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
