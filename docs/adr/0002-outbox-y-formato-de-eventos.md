# ADR-0002: Transactional outbox y formato de los eventos en `triaje.eventos`

- **Estado:** Aceptado
- **Fecha:** 2026-11-09

## Contexto

Cada cambio clínico (registro, entrada a consulta, alta, escalamiento) debe quedar en PostgreSQL, en la
auditoría (RN-04) **y** publicarse en Kafka para el asistente (vista de urgencias) y el servicio de alertas.
Escribir en la base y luego publicar en Kafka desde el mismo request es un *dual write*:

- Si Kafka falla después del commit, el evento se pierde y la vista de urgencias o la alerta quedan mal.
- Si se publica antes del commit y la transacción falla, se anuncia un hecho que nunca ocurrió.
- Si Kafka está caído y el request espera, la API de triaje queda acoplada a Kafka. El enunciado exige que
  el triaje no dependa de componentes externos.

## Decisión

### 1. Transactional outbox con relay reactivo

- El caso de uso escribe paciente + auditoría + fila en `outbox_eventos` en **una sola transacción R2DBC**.
- `OutboxRelay` (reactor-kafka) publica de forma independiente:
  `Flux.interval` → `onBackpressureDrop` → `concatMap` (un lote a la vez) →
  `SELECT … FOR UPDATE SKIP LOCKED` → `KafkaSender.send` → `UPDATE publicado_en` solo de lo confirmado.
- Si el lote viene lleno, se drena el siguiente de inmediato (`expand`); los ticks que llegan mientras hay
  un lote en curso se descartan. Esa es la contrapresión: nunca se acumulan lotes en memoria.
- `SKIP LOCKED` permite varias réplicas del servicio sin publicar el mismo evento dos veces ni bloquearse.

### 2. Garantías de entrega

| Aspecto | Decisión |
|---|---|
| Semántica | **At-least-once.** Si el proceso cae entre el ack de Kafka y el `UPDATE`, el evento se reenvía |
| Deduplicación | Responsabilidad del consumidor, por `eventId` (UUID único por evento) |
| Orden | Por paciente: key = `pacienteId` (misma partición), lote en orden de inserción, productor idempotente (`enable.idempotence=true`, `acks=all`) y `stopOnError`: lo no confirmado se reintenta en el mismo orden |
| Kafka caído | La API responde normal (verificado: 201 en ~20 ms con el broker detenido); los eventos esperan en PostgreSQL y salen al volver el broker. `intentos` registra los fallos |

### 3. Formato del mensaje: JSON versionado

- **Key:** `pacienteId` (string UUID).
- **Headers:** `eventId`, `eventType`, `eventVersion`, `contentType=application/json`, `source=triage-service`.
  Los consumidores pueden filtrar por tipo sin deserializar el cuerpo.
- **Value (v1):**

```json
{
  "eventId": "6749c0d7-da87-407e-a9b6-2bd36efbd81f",
  "eventType": "paciente_registrado",
  "eventVersion": 1,
  "occurredAt": "2026-11-09T21:31:09.463Z",
  "pacienteId": "d4021d68-2435-491f-bab6-f0e405218086",
  "nombreCompleto": "Maria Gomez",
  "quejaPrincipal": "Dificultad para respirar",
  "nivel": "URGENTE",
  "estadoAnterior": null,
  "estadoNuevo": "REGISTRADO",
  "escalado": false,
  "slaMinutos": 10,
  "fechaRegistro": "2026-11-09T21:31:09.463Z",
  "detalle": "Clasificado como URGENTE: 90 ≤ SpO2 ≤ 93 (91)"
}
```

`eventType` ∈ `paciente_registrado`, `paciente_en_consulta`, `paciente_alta`, `paciente_escalado`.
Los campos nulos se omiten. Cada evento es **autocontenido** (*event-carried state transfer*): el
asistente reconstruye la vista de urgencias (nivel + estado) y el servicio de alertas arma la notificación
(nombre, queja, nivel, SLA) sin consultar la API de triaje.

**Evolución:** los cambios compatibles (campos nuevos opcionales) mantienen `eventVersion`. Un cambio
incompatible incrementa la versión y se publica en paralelo durante la migración. Los consumidores ignoran
campos desconocidos.

## Alternativas consideradas

| Alternativa | Por qué se descartó |
|---|---|
| Publicar en Kafka dentro del request (dual write) | Pérdida o invención de eventos ante fallos; acopla la latencia y disponibilidad de la API a Kafka |
| Transacciones Kafka + base de datos (XA / chained) | No hay transacción distribuida real entre R2DBC y Kafka; complejidad alta |
| CDC con Debezium sobre el outbox | Robusto, pero suma Kafka Connect a la infraestructura local y de Azure; desproporcionado para el alcance. Es la evolución natural si el volumen crece |
| Avro + Schema Registry | Contrato fuerte, pero agrega un componente más y dificulta inspeccionar los mensajes. JSON versionado con contrato documentado es suficiente para 3 servicios del mismo equipo |
| Spring Kafka `KafkaTemplate` | Válido (con `CompletableFuture` → `Mono`), pero reactor-kafka da backpressure y operadores Reactor nativos |

## Consecuencias

- ✅ Estado, auditoría y eventos nunca divergen; la API de triaje no depende de Kafka.
- ✅ Consumidores simples: deduplican por `eventId` y confían en el orden por paciente.
- ⚠️ Latencia de publicación ≈ intervalo del relay (500 ms por defecto, configurable con `OUTBOX_INTERVALO`).
- ⚠️ La tabla `outbox_eventos` crece: en producción se purgarían los publicados con más de N días
  (job de mantenimiento; fuera del alcance de la prueba).
- ⚠️ El lote mantiene la transacción abierta mientras espera los acks, acotada por `delivery.timeout.ms=30s`.
- ⚠️ **reactor-kafka** está en mantenimiento limitado (el proyecto anunció el fin de nuevas funcionalidades).
  Se aísla en `OutboxRelay` y `KafkaConfig`; migrar a `KafkaTemplate` o al cliente nativo afecta solo a esas
  dos clases.
- ⚠️ Los eventos incluyen nombre y queja del paciente (datos sensibles) para que la alerta sea útil. En
  Azure, el topic debe tener acceso restringido por ACL/SAS y cifrado en tránsito (TLS).
