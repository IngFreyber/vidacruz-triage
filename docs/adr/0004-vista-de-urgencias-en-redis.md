# ADR-0004: Vista de urgencias materializada en Redis desde `triaje.eventos`

- **Estado:** Aceptado
- **Fecha:** 2026-11-09

## Contexto

RF-04 parte B: `GET /asistente/estado-urgencias` devuelve los pacientes activos (no `ALTA`) por nivel. El
asistente **debe reconstruir** ese estado consumiendo `triaje.eventos`, sin llamar a la API de triaje, y
persistirlo en Redis o en una base NoSQL. La entrega de Kafka es *at-least-once* (ADR-0002): habrá
reentregas, y tras un reinicio o un rebalanceo se reprocesan mensajes.

## Decisión

### Modelo en Redis

| Clave | Tipo | Contenido |
|---|---|---|
| `urgencias:activos` | Hash | nivel → pacientes activos |
| `urgencias:paciente:{id}` | Hash | `nivel`, `estado`, `ts` (último evento aplicado). Si el paciente está de alta, expira a los 7 días |
| `urgencias:evento:{eventId}` | String (TTL 7 días) | Marca de deduplicación |
| `urgencias:actualizado-en` | String | epoch ms del último evento aplicado |

La consulta lee un único hash, así que es O(5) e independiente de la cantidad de pacientes.

### Aplicación atómica con Lua (`redis/aplicar_evento.lua`)

Cada evento se aplica en **un solo script**, que Redis ejecuta sin intercalar otros comandos:

1. **Deduplicación:** `SET urgencias:evento:{eventId} NX EX`. Si ya existía, termina (reentrega).
2. **`ALTA` es terminal:** ningún evento posterior reactiva a un paciente dado de alta.
3. **Eventos obsoletos:** si `occurredAt` es anterior al último evento aplicado del paciente, se descarta.
4. **Contadores:** se resta del nivel previo si el paciente estaba activo y se suma al nivel actual si sigue
   activo. Un escalamiento (mismo nivel, sigue `REGISTRADO`) da un neto de 0.

No es posible duplicar ni perder conteos aunque varias réplicas o reentregas apliquen el mismo evento a la
vez (probado con 20 entregas concurrentes: se aplica 1).

### Consumo reactivo (reactor-kafka)

- `groupBy(partición)` + `concatMap`: orden estricto por partición (y por paciente, key = `pacienteId`) y
  paralelismo entre particiones. Las réplicas del asistente se reparten las particiones por consumer group.
- El offset se confirma **después** de aplicar en Redis, con commit por lotes cada 1 s o cada 100 mensajes.
- **Redis caído:** el registro se reintenta con backoff exponencial (hasta 30 s) y la partición deja de pedir
  mensajes. reactor-kafka pausa el consumo en lugar de acumularlos en memoria (backpressure).
  `GET /asistente/estado-urgencias` responde **503** mientras tanto. Verificado: al volver Redis, la vista se
  puso al día en ~3 s.
- **Mensaje fuera de contrato** (*poison message*): se registra con topic, partición y offset y se descarta,
  para no bloquear la partición.
- **Primer arranque:** `auto.offset.reset=earliest`, así la vista se reconstruye desde el inicio del topic.

### Reconstrucción (runbook)

Si se pierde Redis, se borran las claves `urgencias:*` y se reinicia el consumer group al inicio:

```bash
kafka-consumer-groups.sh --bootstrap-server <broker> --group asistente-vista-urgencias \
  --topic triaje.eventos --reset-offsets --to-earliest --execute
```

El reproceso es seguro porque el script es idempotente. Verificado: la vista reconstruida coincidió exactamente
con `SELECT nivel, count(*) FROM pacientes WHERE estado <> 'ALTA'` en PostgreSQL.

## Alternativas consideradas

| Alternativa | Por qué se descartó |
|---|---|
| `INCR`/`DECR` sueltos sin estado por paciente | No tolera reentregas ni sabe de qué nivel restar al dar el alta |
| `MULTI`/`EXEC` con `WATCH` | Requiere lectura previa y reintentos optimistas; Lua es atómico en un solo viaje de red |
| Recontar con `SCAN` de todos los pacientes en cada consulta | O(n) por consulta; el hash de contadores es O(1) |
| MongoDB / Cosmos DB | Válidos, pero Redis ya es parte del stack exigido y es el almacén natural para un contador en tiempo real |
| Kafka Streams / KTable | Potente, pero agrega un runtime y su propio almacenamiento de estado; desproporcionado para 5 contadores |
| Consultar la API de triaje | Prohibido por el enunciado y acoplaría el asistente a la disponibilidad de triaje |

## Consecuencias

- ✅ Lectura O(1), consistencia eventual de milisegundos a segundos, tolerancia total a reentregas y reprocesos.
- ✅ El asistente funciona aunque triaje esté caído: sirve la última vista conocida.
- ⚠️ **Redis sin modo cluster:** las 4 claves del script no comparten hash slot. Azure Cache for Redis
  Basic/Standard (no cluster) es suficiente. En cluster habría que mover los contadores por paciente a hash tags.
- ⚠️ La deduplicación recuerda los eventIds 7 días. Un reproceso de eventos más antiguos lo cubren los guards de
  `ALTA` terminal y de evento obsoleto.
- ⚠️ La retención del topic debe superar la estancia máxima de un paciente activo para poder reconstruir la vista
  desde cero (7 días por defecto en Kafka).
- ⚠️ Un mensaje descartado por estar fuera de contrato solo queda en logs. En producción se enviaría a un topic
  DLQ (`triaje.eventos.dlq`) con alerta.
