# VidaCruz — Sistema de triaje digital con asistente clínico

Backend reactivo (Spring Boot 3 + WebFlux + Project Reactor) compuesto por tres microservicios
que se integran de forma asíncrona mediante Kafka.

> 🚧 Proyecto en construcción por etapas. Ver [Plan de etapas](#plan-de-etapas).

## Microservicios

| Servicio | Puerto | Responsabilidad | Persistencia |
|---|---|---|---|
| `triage-service` | 8081 | Registro y clasificación de pacientes (RN-01), flujo de estados, SLA y escalamiento, auditoría clínica, reporte de operación | PostgreSQL (R2DBC) |
| `asistente-service` | 8082 | Asistente clínico RAG sobre protocolos internos (anti-alucinación) y vista en tiempo real del estado de urgencias | Redis + vector store en memoria |
| `alertas-service` | 8083 | Alertas de escalamiento al equipo médico (RF-06): SSE + webhook Microsoft Teams | Redis |

Integración: topic Kafka `triaje.eventos` (`paciente_registrado`, `paciente_en_consulta`,
`paciente_alta`, `paciente_escalado`).

## Stack

Java 21 · Spring Boot 3.5 · Spring WebFlux · R2DBC PostgreSQL · Reactor Kafka · Redis reactivo ·
Spring AI (Google Gemini vía API compatible con OpenAI + embeddings ONNX locales) · Resilience4j ·
Micrometer / Prometheus / OpenTelemetry · Testcontainers · Docker · Kubernetes (Azure)

## Ejecución local

Requisitos: Docker. (Para desarrollo: JDK 21.)

```bash
cp .env.example .env       # completar LLM_API_KEY (Google AI Studio)
docker compose up -d       # levanta PostgreSQL, Kafka (topic triaje.eventos) y Redis
./mvnw verify              # compila y ejecuta los tests (los de integración usan Testcontainers)
```

Para ejecutar el servicio de triaje fuera de Docker (desarrollo). PostgreSQL se publica en el puerto
**15432** del host para no chocar con instalaciones locales:

```bash
DB_PORT=15432 DB_PASSWORD=triage_local_pwd java -jar triage-service/target/triage-service.jar
```

## API de triage (`triage-service`, puerto 8081)

| Método | Ruta | Descripción | Respuestas |
|---|---|---|---|
| `POST` | `/triage/pacientes` | Registra y clasifica (RF-01) | 201 + `Location`, 400 |
| `GET` | `/triage/pacientes/{id}` | Datos completos, estado, flags y tiempo en el estado actual | 200, 404 |
| `PATCH` | `/triage/pacientes/{id}/estado` | Transición de estado `{"estado": "EN_CONSULTA"}` (RF-02) | 200, 400, 404, 409 |
| `GET` | `/triage/pacientes/{id}/auditoria` | Bitácora clínica en solo lectura (RN-04) | 200, 404 |
| `GET` | `/triage/reportes/operacion?desde=…&hasta=…` | Reporte por hora (RF-05) | 200, 400 |

Los errores siguen el estándar **RFC 7807** (`application/problem+json`) con `type`, `title`, `status`,
`detail`, `instance`, `timestamp` y, en validaciones, `errores: [{campo, mensaje}]`.

**Atomicidad:** paciente, entrada de auditoría y evento (tabla `outbox_eventos`) se escriben en la misma
transacción R2DBC. **Concurrencia:** las actualizaciones usan control optimista por versión; ante un
conflicto se relee y reintenta, y la transición se vuelve a validar con el estado fresco.
**Eventos:** el `OutboxRelay` publica los eventos pendientes en `triaje.eventos` (key = `pacienteId`,
JSON versionado, entrega at-least-once) y los marca como publicados tras el ack de Kafka. Si Kafka no está
disponible la API sigue respondiendo y los eventos salen al recuperarse el broker (ver ADR-0002).
**Escalamiento (RF-03):** un monitor reactivo evalúa periódicamente a los pacientes en `REGISTRADO` y
escala, una única vez, a los que superaron su SLA (flag persistente, auditoría y evento `paciente_escalado`).
Para verlo en segundos:

```bash
DB_PORT=15432 DB_PASSWORD=triage_local_pwd TRIAGE_SLA_CRITICO=20s TRIAGE_ESCALAMIENTO_INTERVALO=3s \
  java -jar triage-service/target/triage-service.jar
```

**Reporte de operación (RF-05):** `GET /triage/reportes/operacion?desde=2026-11-09&hasta=2026-11-10`
devuelve, por cada hora del rango `[desde, hasta)` en UTC (incluidas las horas vacías) y en un `resumen`
del rango completo: total de registrados, conteo por nivel, pacientes atendidos, espera promedio y **p95**
(registro → primera entrada a consulta, en minutos). Se calcula en **una sola consulta SQL**, sin iterar en
memoria:

| Técnica | Para qué |
|---|---|
| `generate_series` | Una fila por cada hora del rango, también sin pacientes |
| `ROW_NUMBER() OVER (PARTITION BY paciente_id ORDER BY ocurrido_en)` | Primera entrada a consulta de cada paciente desde la auditoría |
| `COUNT(*) FILTER (WHERE nivel = …)` | Conteo por nivel en una sola pasada |
| `percentile_cont(0.95) WITHIN GROUP (ORDER BY …)` | p95 continuo (interpolado) de la espera |
| `GROUP BY ROLLUP (hora)` + `GROUPING()` | Filas por hora y fila de resumen en la misma consulta |
| CTE (`WITH`) | Pasos legibles: horas → registrados → primera consulta → esperas → agregados |

Fechas ISO 8601 con zona (`2026-11-09T10:00:00Z`, `…-05:00`), sin zona (UTC) o solo fecha. Rango máximo 31 días
(`TRIAGE_REPORTES_RANGO_MAXIMO`). La espera se atribuye a la hora de **registro**; los pacientes que aún no
entraron a consulta (o tuvieron alta directa) cuentan en los totales pero no en la espera.

**Inmutabilidad de la auditoría:** además de no existir endpoint de borrado, un trigger de PostgreSQL
rechaza `UPDATE`, `DELETE` y `TRUNCATE` sobre `auditoria_clinica`.

## API del asistente (`asistente-service`, puerto 8082)

| Método | Ruta | Descripción | Respuestas |
|---|---|---|---|
| `GET` | `/asistente/estado-urgencias` | Pacientes activos (no `ALTA`) por nivel, en tiempo real (RF-04 B) | 200, 503 |

```json
{"totalActivos":6,"porNivel":{"CRITICO":2,"URGENTE":1,"URGENTE_2":0,"NO_URGENTE":3,"NO_URGENTE_2":0},
 "actualizadoEn":"2026-11-09T22:39:18.630Z","fuente":"kafka:triaje.eventos"}
```

La vista se reconstruye **solo** consumiendo `triaje.eventos` (nunca llama a la API de triaje) y se guarda en
Redis. Cada evento se aplica con un script Lua atómico e idempotente: deduplica por `eventId`, trata `ALTA` como
estado terminal y descarta eventos obsoletos. Si Redis cae, el consumidor pausa la partición y reintenta, y el
endpoint responde 503; al recuperarse, la vista se pone al día sola (ver ADR-0004).

## Decisiones de arquitectura (ADR)

Ver [`docs/adr`](docs/adr).

| ADR | Decisión |
|---|---|
| [0001](docs/adr/0001-descomposicion-en-microservicios.md) | Descomposición en tres microservicios y arquitectura hexagonal |
| [0002](docs/adr/0002-outbox-y-formato-de-eventos.md) | Transactional outbox, garantías de entrega y formato JSON de los eventos |
| [0003](docs/adr/0003-monitor-de-sla-y-escalamiento-idempotente.md) | Monitor de SLA por scheduler reactivo y escalamiento idempotente |
| [0004](docs/adr/0004-vista-de-urgencias-en-redis.md) | Vista de urgencias materializada en Redis desde `triaje.eventos` |

## Supuestos ante ambigüedades del enunciado

| # | Ambigüedad | Supuesto adoptado |
|---|---|---|
| S1 | RN-01 no contempla hipotermia ni hipertensión aislada | Temperatura < 38.0 °C y P. sistólica > 100 mmHg no elevan el nivel por sí solas (E1 es CRITICO por su FC) |
| S2 | "La queja principal pertenece a la lista administrativa" | Igualdad tras normalizar (minúsculas, sin tildes, sin espacios repetidos ni puntuación final). No se usa "contiene" para no bajar la prioridad de quejas clínicas que mencionen un término administrativo (p. ej. "Seguimiento de herida infectada") |
| S3 | Longitud de la queja principal no especificada | Obligatoria, de 1 a 500 caracteres |
| S4 | "Superó su SLA" | Se escala cuando `ahora > fechaRegistro + SLA` (estrictamente mayor) |
| S5 | Auditoría del escalamiento (no cambia el estado) | Entrada con estado anterior = estado nuevo = `REGISTRADO`, tipo `paciente_escalado` y detalle "Flag escalado false → true" |
| S6 | Un paciente escalado que entra a consulta | Conserva el flag `escalado = true` (es histórico, sirve para el reporte y la auditoría) |
| S7 | Validación de entrada | Se reportan **todas** las violaciones en una sola respuesta 400, cada una con su campo |
| S8 | Cambio de configuración de SLA con pacientes en espera | El SLA se fija al registrar; los cambios aplican solo a nuevos registros |
| S9 | `slaMinutos` con SLA configurado en segundos (pruebas) | Se redondea hacia arriba (20 s → 1 min); `vencimientoSla` da el instante exacto |
| S10 | RF-05: a qué hora pertenece una espera | A la hora de **registro** del paciente (la cohorte que llegó en esa hora), aunque la consulta ocurra después |
| S11 | RF-05: pacientes sin consulta (en espera o alta directa) | Se cuentan en totales y por nivel, pero no en promedio ni p95 (no tienen "primera entrada a consulta"); `pacientesAtendidos` explicita la base del cálculo |
| S12 | RF-05: zona horaria de las horas | UTC, declarada en la respuesta (`zonaHoraria`); `hasta` es exclusivo |

## Plan de etapas

- [x] 0. Andamiaje: monorepo Maven, infraestructura local, ADR inicial
- [x] 1. Dominio de triage: validaciones, clasificación RN-01, SLA RN-02, máquina de estados
- [x] 2. Persistencia y API de triage (RF-01, RF-02, RN-04)
- [x] 3. Publicación de eventos (outbox → Kafka)
- [x] 4. SLA y escalamiento idempotente (RF-03, RN-03)
- [x] 5. Reporte de operación con SQL avanzado (RF-05)
- [x] 6. Asistente — vista de urgencias desde Kafka en Redis (RF-04 B)
- [ ] 7. Asistente — RAG con citas, anti-alucinación y modo degradado (RF-04 A, RN-05)
- [ ] 8. Servicio de alertas (RF-06)
- [ ] 9. Seguridad y observabilidad
- [ ] 10. Tests de integración con Testcontainers
- [ ] 11. Contenerización completa (`docker compose up`)
- [ ] 12. Kubernetes / Azure
- [ ] 13. Documentación final
