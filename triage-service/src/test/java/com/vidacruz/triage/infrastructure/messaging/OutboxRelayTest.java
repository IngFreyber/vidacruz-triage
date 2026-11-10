package com.vidacruz.triage.infrastructure.messaging;

import com.vidacruz.triage.infrastructure.config.EventosProperties;
import com.vidacruz.triage.infrastructure.persistence.R2dbcOutboxRepository;
import com.vidacruz.triage.infrastructure.persistence.R2dbcOutboxRepository.EventoPendiente;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderResult;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OutboxRelay: comportamiento ante éxito y fallo de Kafka")
class OutboxRelayTest {

    private final R2dbcOutboxRepository outbox = mock(R2dbcOutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaSender<String, String> sender = mock(KafkaSender.class);
    private final TransactionalOperator tx = mock(TransactionalOperator.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC);
    private final EventosProperties props = new EventosProperties("triaje.eventos", "localhost:9092",
            new EventosProperties.Outbox(true, Duration.ofMillis(200), 10));

    private OutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(tx.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outbox.marcarPublicados(anyList(), any())).thenAnswer(inv -> Mono.just((long) ((List<?>) inv.getArgument(0)).size()));
        when(outbox.sumarIntento(anyList())).thenReturn(Mono.just(0L));
        relay = new OutboxRelay(outbox, sender, tx, props, clock);
    }

    @Test
    void marcaPublicadosTodosLosConfirmados() {
        when(outbox.bloquearPendientes(anyInt())).thenReturn(Flux.just(pendiente(1), pendiente(2), pendiente(3)));
        doReturn(Flux.just(ok(1L), ok(2L), ok(3L))).when(sender).send(any());

        StepVerifier.create(relay.publicarLote()).expectNext(3).verifyComplete();

        verify(outbox).marcarPublicados(List.of(1L, 2L, 3L), clock.instant());
        verify(outbox, never()).sumarIntento(anyList());
    }

    @Test
    @DisplayName("Si Kafka falla a mitad del lote, solo se marca lo confirmado y el resto queda pendiente")
    void fallaParcialDejaPendientesLosNoConfirmados() {
        when(outbox.bloquearPendientes(anyInt())).thenReturn(Flux.just(pendiente(1), pendiente(2), pendiente(3)));
        doReturn(Flux.just(ok(1L)).concatWith(Flux.error(new TimeoutException("broker no disponible"))))
                .when(sender).send(any());

        StepVerifier.create(relay.publicarLote()).expectNext(1).verifyComplete();

        verify(outbox).marcarPublicados(List.of(1L), clock.instant());
        verify(outbox).sumarIntento(List.of(2L, 3L));
    }

    @Test
    @DisplayName("Con Kafka caído no se marca nada y el drenado no propaga el error")
    void kafkaCaidoNoRompeElRelay() {
        when(outbox.bloquearPendientes(anyInt())).thenReturn(Flux.just(pendiente(1)));
        doReturn(Flux.error(new TimeoutException("broker no disponible"))).when(sender).send(any());

        StepVerifier.create(relay.drenar()).expectNext(0).verifyComplete();

        verify(outbox).marcarPublicados(List.of(), clock.instant());
        verify(outbox).sumarIntento(List.of(1L));
    }

    @Test
    void sinPendientesNoEnviaNada() {
        when(outbox.bloquearPendientes(anyInt())).thenReturn(Flux.empty());

        StepVerifier.create(relay.publicarLote()).expectNext(0).verifyComplete();

        verify(sender, never()).send(any());
        verify(outbox, never()).marcarPublicados(anyList(), eq(clock.instant()));
    }

    private static EventoPendiente pendiente(long id) {
        return new EventoPendiente(id, UUID.randomUUID(), UUID.randomUUID(), "paciente_registrado", "{}", null);
    }

    @SuppressWarnings("unchecked")
    private static SenderResult<Long> ok(Long id) {
        SenderResult<Long> r = mock(SenderResult.class);
        when(r.correlationMetadata()).thenReturn(id);
        return r;
    }
}
