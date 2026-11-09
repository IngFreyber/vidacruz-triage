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

Los errores siguen el estándar **RFC 7807** (`application/problem+json`) con `type`, `title`, `status`,
`detail`, `instance`, `timestamp` y, en validaciones, `errores: [{campo, mensaje}]`.

**Atomicidad:** paciente, entrada de auditoría y evento (tabla `outbox_eventos`) se escriben en la misma
transacción R2DBC. **Concurrencia:** las actualizaciones usan control optimista por versión; ante un
conflicto se relee y reintenta, y la transición se vuelve a validar con el estado fresco.
**Eventos:** el `OutboxRelay` publica los eventos pendientes en `triaje.eventos` (key = `pacienteId`,
JSON versionado, entrega at-least-once) y los marca como publicados tras el ack de Kafka. Si Kafka no está
disponible la API sigue respondiendo y los eventos salen al recuperarse el broker (ver ADR-0002).
**Inmutabilidad de la auditoría:** además de no existir endpoint de borrado, un trigger de PostgreSQL
rechaza `UPDATE`, `DELETE` y `TRUNCATE` sobre `auditoria_clinica`.

## Decisiones de arquitectura (ADR)

Ver [`docs/adr`](docs/adr).

| ADR | Decisión |
|---|---|
| [0001](docs/adr/0001-descomposicion-en-microservicios.md) | Descomposición en tres microservicios y arquitectura hexagonal |
| [0002](docs/adr/0002-outbox-y-formato-de-eventos.md) | Transactional outbox, garantías de entrega y formato JSON de los eventos |

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

## Plan de etapas

- [x] 0. Andamiaje: monorepo Maven, infraestructura local, ADR inicial
- [x] 1. Dominio de triage: validaciones, clasificación RN-01, SLA RN-02, máquina de estados
- [x] 2. Persistencia y API de triage (RF-01, RF-02, RN-04)
- [x] 3. Publicación de eventos (outbox → Kafka)
- [ ] 4. SLA y escalamiento idempotente (RF-03, RN-03)
- [ ] 5. Reporte de operación con SQL avanzado (RF-05)
- [ ] 6. Asistente — vista de urgencias desde Kafka en Redis (RF-04 B)
- [ ] 7. Asistente — RAG con citas, anti-alucinación y modo degradado (RF-04 A, RN-05)
- [ ] 8. Servicio de alertas (RF-06)
- [ ] 9. Seguridad y observabilidad
- [ ] 10. Tests de integración con Testcontainers
- [ ] 11. Contenerización completa (`docker compose up`)
- [ ] 12. Kubernetes / Azure
- [ ] 13. Documentación final
