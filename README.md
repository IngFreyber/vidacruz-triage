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
docker compose up -d       # Etapa 0: levanta PostgreSQL, Kafka (topic triaje.eventos) y Redis
./mvnw verify              # compila y ejecuta los tests
```

## Decisiones de arquitectura (ADR)

Ver [`docs/adr`](docs/adr).

| ADR | Decisión |
|---|---|
| [0001](docs/adr/0001-descomposicion-en-microservicios.md) | Descomposición en tres microservicios y arquitectura hexagonal |

## Plan de etapas

- [x] 0. Andamiaje: monorepo Maven, infraestructura local, ADR inicial
- [ ] 1. Dominio de triage: validaciones, clasificación RN-01, SLA RN-02, máquina de estados
- [ ] 2. Persistencia y API de triage (RF-01, RF-02, RN-04)
- [ ] 3. Publicación de eventos (outbox → Kafka)
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
