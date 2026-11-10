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

## Ejecución local — un solo comando

Requisitos: **Docker** (con ~8 GB de memoria asignada) y **~5 GB libres** para las imágenes. No hace falta Java ni
Maven: todo se compila dentro de Docker.

```bash
cp .env.example .env          # opcional: LLM_API_KEY (https://aistudio.google.com/api-keys, plan gratuito)
docker compose up --build     # PostgreSQL, Kafka, Redis y los 3 microservicios
```

Listo cuando `docker compose ps` muestra los tres servicios `healthy`: **triage :8081**, **asistente :8082** y
**alertas :8083**. Las API keys de ejemplo de `.env.example` sirven tal cual en local. Sin `LLM_API_KEY` todo
funciona salvo las consultas al asistente que necesitan el LLM, que responden 503 (modo degradado).

```bash
# Prueba rápida (clave de ejemplo de .env.example)
curl -X POST localhost:8081/triage/pacientes -H 'X-API-Key: cambia-esta-clave-triage' \
     -H 'Content-Type: application/json' \
     -d '{"nombreCompleto":"Ana Pérez","edad":58,"frecuenciaCardiaca":130,"presionSistolica":180,
          "presionDiastolica":110,"spo2":97,"temperatura":36.8,"dolor":8,"gcs":15,"quejaPrincipal":"Dolor torácico"}'
```

**Ver escalamientos y alertas en segundos** (en lugar de esperar los SLA reales):

```bash
SLA_CRITICO=20s TRIAGE_ESCALAMIENTO_INTERVALO=3s docker compose up -d
curl -N "localhost:8083/alertas/stream?api_key=cambia-esta-clave-alertas"     # tablero SSE
```

**Trazado distribuido en Jaeger** (http://localhost:16686): una petición a triage aparece como una sola traza que
continúa en los consumidores de Kafka del asistente y de alertas.

```bash
TRACING_EXPORTAR=true docker compose --profile observabilidad up -d
```

### Imágenes

| Imagen | Base | Tamaño | Notas |
|---|---|---|---|
| `vidacruz/triage-service` | `eclipse-temurin:21-jre-alpine` | 412 MB | UID 10001 |
| `vidacruz/alertas-service` | `eclipse-temurin:21-jre-alpine` | 419 MB | UID 10001 |
| `vidacruz/asistente-service` | `eclipse-temurin:21-jre-noble` | 1.7 GB | Incluye el modelo ONNX (~460 MB) y ONNX Runtime, que necesita glibc; por eso no usa Alpine |

Dockerfiles multi-stage: compilación con Maven (caché de dependencias con BuildKit), capas de Spring Boot
(dependencias separadas del código), usuario sin privilegios, `MaxRAMPercentage=75`, `ExitOnOutOfMemoryError` y
`HEALTHCHECK` contra la probe de readiness. El modelo de embeddings va **dentro** de la imagen: el contenedor no
descarga nada al arrancar.

### Desarrollo sin Docker

`./mvnw verify` compila y ejecuta los 218 tests (Testcontainers) con cobertura. Para ejecutar un servicio
fuera de Docker, con la infraestructura de compose (PostgreSQL publicado en **15432** para no chocar con
instalaciones locales):

```bash
docker compose up -d postgres kafka kafka-init redis
set -a; . ./.env; set +a     # carga las API keys y la configuración local
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
DB_PORT=15432 DB_PASSWORD=triage_local_pwd SLA_CRITICO=20s TRIAGE_ESCALAMIENTO_INTERVALO=3s \
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
| `POST` | `/asistente/consulta` | Consulta en lenguaje natural sobre protocolos, con RAG y citas (RF-04 A) | 200, 400, 503 |
| `GET` | `/asistente/estado-urgencias` | Pacientes activos (no `ALTA`) por nivel, en tiempo real (RF-04 B) | 200, 503 |

```bash
curl -X POST localhost:8082/asistente/consulta -H 'Content-Type: application/json' \
  -d '{"pregunta":"¿Cuáles son las medidas iniciales ante una crisis hipertensiva?"}'
```
```json
{"respuesta":"Las medidas iniciales ante una crisis hipertensiva son:\n- Reconfirmar la presión tras 5 minutos de reposo.\n- No reducir la presión más de un 25% en la primera hora.\n- Vigilar signos de encefalopatía.",
 "fuentes":[{"protocolo":"PROTO-001","seccion":"Medidas iniciales"}],"estado":"RESPONDIDA"}
```

`estado` puede ser `RESPONDIDA` (con al menos una fuente válida), `SIN_PROTOCOLO` (solo el aviso "no existe
protocolo disponible", sin contenido clínico) o `RESPUESTA_DESCARTADA` (el LLM citó algo que no se le entregó o
no citó: se descarta todo el texto generado). Si el LLM falla o el circuito está abierto → **503** con
`Retry-After`.

**Anti-alucinación en tres capas** (ADR-0005): (1) umbral de similitud calibrado: sin fragmento relevante se
responde el aviso **sin llamar al LLM**; (2) el LLM recibe solo los fragmentos recuperados, con temperatura 0 y
salida JSON; (3) cada cita se valida contra los fragmentos entregados. Embeddings **locales** (ONNX multilingüe):
la recuperación no depende del proveedor. El corpus está en
[`asistente-service/src/main/resources/protocolos`](asistente-service/src/main/resources/protocolos).

```json
{"totalActivos":6,"porNivel":{"CRITICO":2,"URGENTE":1,"URGENTE_2":0,"NO_URGENTE":3,"NO_URGENTE_2":0},
 "actualizadoEn":"2026-11-09T22:39:18.630Z","fuente":"kafka:triaje.eventos"}
```

La vista se reconstruye **solo** consumiendo `triaje.eventos` (nunca llama a la API de triaje) y se guarda en
Redis. Cada evento se aplica con un script Lua atómico e idempotente: deduplica por `eventId`, trata `ALTA` como
estado terminal y descarta eventos obsoletos. Si Redis cae, el consumidor pausa la partición y reintenta, y el
endpoint responde 503; al recuperarse, la vista se pone al día sola (ver ADR-0004).

## API de alertas (`alertas-service`, puerto 8083) — RF-06

| Método | Ruta | Descripción | Respuestas |
|---|---|---|---|
| `GET` | `/alertas/stream` | **SSE** para tableros: pendientes al conectar + `alerta` / `alerta_atendida` en tiempo real | 200 |
| `GET` | `/alertas?estado=PENDIENTE&limite=50` | Historial (más recientes primero) | 200, 400 |
| `GET` | `/alertas/{id}` | Detalle de una alerta | 200, 404 |
| `POST` | `/alertas/{id}/atencion` | Un profesional toma la alerta `{"atendidaPor":"Dra. Salas"}` (idempotente) | 200, 400, 404 |

```bash
curl -N localhost:8083/alertas/stream     # deja la conexión abierta y muestra las alertas al escalar
```

**Decisión (ADR-0007):** el equipo médico de turno ve las alertas en tiempo real en un tablero conectado por
**Server-Sent Events**, y opcionalmente recibe una **Adaptive Card en Microsoft Teams** (`TEAMS_WEBHOOK_URL`, webhook
de Workflows). Cada alerta queda en un historial con **quién la tomó y cuándo**. Es idempotente por `eventId`;
Redis Pub/Sub la difunde a todas las réplicas, y una caída de Teams (con retry y circuit breaker) no afecta al
tablero ni al historial.

## Despliegue en Azure (AKS)

**URL pública: http://57.162.225.46** (las API keys del entorno desplegado se entregan por un canal privado; no
están en el repositorio).

| Servicio | Endpoints públicos |
|---|---|
| triage | `http://57.162.225.46/triage/pacientes`, `/triage/pacientes/{id}`, `/triage/pacientes/{id}/estado`, `/triage/pacientes/{id}/auditoria`, `/triage/reportes/operacion` |
| asistente | `http://57.162.225.46/asistente/consulta`, `/asistente/estado-urgencias` |
| alertas | `http://57.162.225.46/alertas`, `/alertas/{id}`, `/alertas/{id}/atencion`, `/alertas/stream?api_key=…` |

```bash
curl -X POST http://57.162.225.46/triage/pacientes -H "X-API-Key: <TRIAGE_API_KEY>" \
     -H 'Content-Type: application/json' -d '{"nombreCompleto":"Ana Pérez","edad":58,"frecuenciaCardiaca":130,
     "presionSistolica":180,"presionDiastolica":110,"spo2":97,"temperatura":36.8,"dolor":8,"gcs":15,
     "quejaPrincipal":"Dolor torácico"}'
```

**Arquitectura en Azure:** AKS (1 nodo `Standard_D4as_v4`, East US) con los 3 servicios (triage ×2, alertas ×2,
asistente ×1) más PostgreSQL, Kafka y Redis en StatefulSets con discos administrados. Las imágenes se publican en
Azure Container Registry, y un único ingress NGINX administrado expone solo las rutas de negocio. Decisiones,
alternativas para producción y los 5 problemas reales encontrados al desplegar: [ADR-0009](docs/adr/0009-despliegue-en-azure-aks.md).

**Manifiestos:** [`k8s/`](k8s) (Kustomize: Deployments, Services, StatefulSets, Job, Ingress, ConfigMap).
**Scripts:** [`infra/azure/`](infra/azure).

```bash
az login
bash infra/azure/desplegar.sh      # crea ACR + AKS, publica imágenes, genera API keys, despliega y prueba
bash infra/azure/apagar.sh         # detiene el nodo (ahorra crédito) · encender.sh lo reanuda
bash infra/azure/eliminar.sh       # borra todo (pide confirmación)
```

## Tests

```bash
./mvnw verify      # 218 tests (unitarios + integración) y reporte de cobertura por servicio
```

Los tests de integración usan **Testcontainers** con los mismos motores de producción (PostgreSQL 16, Kafka 3.9,
Redis 7). El **LLM está simulado** (`@MockitoBean ChatModel`): los tests no necesitan API key ni red, y los
embeddings y la recuperación sí son reales. **BlockHound** está activo en todos los tests de integración: si
algo bloquea el event loop, el test falla.

| Servicio | Tests | Cobertura de líneas | Cobertura de ramas | Reporte |
|---|---|---|---|---|
| `triage-service` | 152 | **96 %** | 77 % | `triage-service/target/site/jacoco/index.html` |
| `asistente-service` | 45 | **93 %** | 67 % | `asistente-service/target/site/jacoco/index.html` |
| `alertas-service` | 21 | **90 %** | 67 % | `alertas-service/target/site/jacoco/index.html` |

### Matriz de trazabilidad: requerimiento → tests

| Requerimiento | Qué se verifica | Tests |
|---|---|---|
| **RN-01** Clasificación | E1–E6 del enunciado, límites exactos de cada criterio (FC 39/40/89/90/120/121, SpO2, PAS, GCS, temperatura, dolor), mayor gravedad gana, quejas administrativas | `ClasificadorTriageTest` (unitario), `PacienteApiIntegrationTest.Registro` (E1–E6 por API) |
| **RN-02** SLA | SLA por nivel y redondeo de minutos | `PoliticaSlaTest`, `PacienteTest`, `PacienteApiIntegrationTest` |
| **RF-01** Registro y validación | Cada rango fuera de límite (ambos extremos) → 400 con campo, diastólica < sistólica, tipos y decimales inválidos, JSON mal formado, todas las violaciones a la vez, respuesta con `id`, `nivel`, `slaMinutos`, `estado`, `fechaRegistro` ISO 8601 | `ValidadorRegistroTest`, `PacienteApiIntegrationTest.Registro` |
| **RF-02** Estados | Matriz completa 3×3 de transiciones, 200/409/404/400, concurrencia optimista (6 peticiones simultáneas → 1 gana) | `EstadoPacienteTest`, `PacienteTest`, `PacienteApiIntegrationTest.FlujoEstados` |
| **RF-03 / RN-03** Escalamiento idempotente | Escala al vencer el SLA y no antes; una sola vez ante evaluaciones repetidas y **3 monitores concurrentes**; nunca `EN_CONSULTA` ni `ALTA`; scheduler real con SLA de 1 s | `PacienteTest.Escalamiento`, `EscalamientoIntegrationTest`, `MonitorSlaIntegrationTest` |
| **RN-04** Auditoría | Cada cambio auditado; una transición inválida no deja rastro; **la base de datos rechaza `UPDATE` y `DELETE`**; no existe endpoint de borrado | `PacienteApiIntegrationTest.Auditoria` |
| **RF-05** Reporte SQL | Conteos por hora y nivel, promedio y **p95 calculados a mano** con datos controlados, horas vacías, resumen con ROLLUP, rango semiabierto, zonas horarias, validaciones | `ReporteOperacionIntegrationTest` |
| **Kafka / outbox** | Orden por paciente, key y headers, marca de publicado, falla parcial de Kafka, traza W3C en el evento | `EventosKafkaIntegrationTest`, `OutboxRelayTest` |
| **RF-04 A / RN-05** RAG | Ejemplos 1 y 2 citan PROTO-001 y PROTO-002; **ejemplo 3 (diabetes) no invoca al LLM**; cita inventada, cita no recuperada, texto sin JSON y `sinInformacion` → sin contenido clínico | `ConsultaRagIntegrationTest`, `ValidadorCitasTest`, `CorpusProtocolosTest` |
| **Modo degradado** | LLM con error → 503 con `Retry-After`; LLM lento → TimeLimiter; circuito abierto tras 4 fallas (0 llamadas después); "sin protocolo" funciona con el LLM caído | `ConsultaRagIntegrationTest.ModoDegradado` |
| **RF-04 B** Vista de urgencias | Ciclo completo, alta directa, duplicados, **20 entregas concurrentes → 1**, alta terminal, eventos obsoletos, mensaje corrupto descartado, Redis caído → 503 | `VistaUrgenciasRedisTest`, `EstadoUrgenciasKafkaIntegrationTest`, `ConsumidorEventosTriajeTest`, `EstadoUrgenciasControllerTest` |
| **RF-06** Alertas | Alerta y Adaptive Card en Teams, reentrega idempotente, otros eventos ignorados, Teams caído (3 reintentos, sin bloquear), SSE en vivo, primera confirmación gana, reconexión de tableros | `AlertasIntegrationTest`, `AlertaTest` |
| **Seguridad** | 401 sin clave o con clave incorrecta, probes públicas, API key por parámetro solo en el SSE | `SeguridadYObservabilidadIntegrationTest` (×3 servicios) |
| **Observabilidad** | Métricas en `/actuator/prometheus`, `X-Trace-Id`, BlockHound activo | `SeguridadYObservabilidadIntegrationTest` (×3 servicios) |

## Seguridad y observabilidad (ADR-0008)

**Autenticación:** todas las rutas exigen la cabecera `X-API-Key` (claves por servicio desde `TRIAGE_API_KEY`,
`ASISTENTE_API_KEY` y `ALERTAS_API_KEY`; sin valor por defecto, así que un servicio sin clave no arranca). Son
públicas solo las probes `/actuator/health/**`, `/actuator/info` y `/actuator/prometheus`. El stream SSE acepta
además `?api_key=`, porque `EventSource` no admite cabeceras.

```bash
curl -H "X-API-Key: $TRIAGE_API_KEY" -H 'Content-Type: application/json' \
     -X POST localhost:8081/triage/pacientes -d @paciente.json
```

**Observabilidad:**
- Logs **JSON (ECS)** con `traceId`/`spanId`, también en código reactivo.
- **Trazado distribuido OpenTelemetry**: el `traceparent` viaja por Kafka desde triage hasta asistente y alertas,
  y cada respuesta trae `X-Trace-Id`.
- **Métricas Prometheus**: `triage_clasificacion_total{nivel}`, `triage_escalamientos_total{nivel}`,
  `asistente_llm_latencia_seconds{resultado,modelo}`, `asistente_consultas_total{estado}`,
  `alertas_emitidas_total`, estado de los circuit breakers y latencia HTTP.
- **Probes** de liveness y readiness por servicio.

**Reactivo verificado:** BlockHound está activo en todos los tests de integración. Detectó y se corrigieron dos
bloqueos reales en el uso de Redis (ver ADR-0008).

## Decisiones de arquitectura (ADR)

Ver [`docs/adr`](docs/adr).

| ADR | Decisión |
|---|---|
| [0001](docs/adr/0001-descomposicion-en-microservicios.md) | Descomposición en tres microservicios y arquitectura hexagonal |
| [0002](docs/adr/0002-outbox-y-formato-de-eventos.md) | Transactional outbox, garantías de entrega y formato JSON de los eventos |
| [0003](docs/adr/0003-monitor-de-sla-y-escalamiento-idempotente.md) | Monitor de SLA por scheduler reactivo y escalamiento idempotente |
| [0004](docs/adr/0004-vista-de-urgencias-en-redis.md) | Vista de urgencias materializada en Redis desde `triaje.eventos` |
| [0005](docs/adr/0005-rag-vector-store-y-anti-alucinacion.md) | RAG: embeddings locales, vector store en memoria, umbral calibrado y anti-alucinación en tres capas |
| [0006](docs/adr/0006-modo-degradado-del-asistente.md) | Modo degradado del asistente: TimeLimiter + CircuitBreaker, 503 explícito |
| [0007](docs/adr/0007-alertas-de-escalamiento.md) | **RF-06**: alertas por SSE + Microsoft Teams, historial con atención, supuestos y alternativas |
| [0008](docs/adr/0008-seguridad-y-observabilidad.md) | Seguridad por API key, logs ECS, métricas, trazado distribuido por Kafka y BlockHound |
| [0009](docs/adr/0009-despliegue-en-azure-aks.md) | Despliegue en Azure AKS: arquitectura, seguridad de pods y problemas reales resueltos |

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
- [x] 7. Asistente — RAG con citas, anti-alucinación y modo degradado (RF-04 A, RN-05)
- [x] 8. Servicio de alertas (RF-06)
- [x] 9. Seguridad y observabilidad
- [x] 10. Tests de integración con Testcontainers + cobertura (JaCoCo) y matriz de trazabilidad
- [x] 11. Contenerización completa (`docker compose up --build`), Jaeger opcional
- [x] 12. Kubernetes / Azure (AKS): URL pública, manifiestos y scripts de despliegue
- [ ] 13. Documentación final
