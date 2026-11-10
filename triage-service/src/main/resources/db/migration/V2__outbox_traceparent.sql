-- V2: contexto de traza W3C (traceparent) del request que originó el evento. El relay lo publica como header
-- de Kafka para que los consumidores (asistente, alertas) continúen la misma traza distribuida.
ALTER TABLE outbox_eventos ADD COLUMN traceparent VARCHAR(55);
