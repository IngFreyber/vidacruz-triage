# ADR-0008: Seguridad por API key y observabilidad (logs, métricas, trazas, BlockHound)

- **Estado:** Aceptado
- **Fecha:** 2026-11-09

## Contexto

Requerimientos técnicos: sin credenciales en el código, autenticación básica de los endpoints (JWT o API key,
a elección), validación de entradas, probes de salud, logs estructurados con correlación, métricas Micrometer
(mínimo: clasificaciones por nivel, escalamientos, latencia del LLM) y, como bonus, trazado distribuido. El
sistema debe ser reactivo de verdad: se penalizan las llamadas bloqueantes en el event loop.

## Decisión

### 1. Autenticación por API key

| Aspecto | Decisión |
|---|---|
| Mecanismo | Cabecera `X-API-Key`, con un `AuthenticationWebFilter` de Spring Security WebFlux |
| Claves | Por servicio: `TRIAGE_API_KEY`, `ASISTENTE_API_KEY`, `ALERTAS_API_KEY`. **Sin valor por defecto: el servicio no arranca si falta.** Mínimo 16 caracteres. Admite varias claves separadas por coma, para rotar sin cortar el servicio |
| Comparación | Tiempo constante (`MessageDigest.isEqual`) recorriendo todas las claves |
| Identidad | El principal es `cliente-<sha256[0..8]>`: identifica al cliente sin exponer la clave en logs |
| Rutas públicas | `/actuator/health/**` (probes), `/actuator/info`, `/actuator/prometheus`. El resto de actuator (`/metrics`) exige clave. En Kubernetes, prometheus solo se expone dentro del clúster, nunca por el ingress |
| SSE | `EventSource` del navegador no permite cabeceras, así que **solo** `/alertas/stream` acepta además `?api_key=`. En cualquier otra ruta el parámetro se rechaza con 401, para que la clave no quede en logs de acceso de rutas comunes |
| Errores | 401/403 en RFC 7807, con `WWW-Authenticate: ApiKey header="X-API-Key"` |
| Estado | Stateless: sin sesión ni CSRF (API consumida por sistemas) |

**¿Por qué API key y no JWT?** Los consumidores son sistemas (estaciones de triaje, tableros, integraciones),
no usuarios finales con login. JWT necesitaría un emisor de tokens (Entra ID / Keycloak) que el enunciado no
provee. La API key cumple "autenticación básica" con menos piezas. **Evolución natural en Azure:** Entra ID con
OAuth2 client credentials; Spring Security lo soporta como resource server sin cambiar los controladores.

### 2. Observabilidad

**Logs estructurados (ECS / JSON)** con el soporte nativo de Spring Boot 3.4+
(`logging.structured.format.console=ecs`). Cada línea lleva `traceId` y `spanId`, **también dentro de los
pipelines reactivos**, gracias a `spring.reactor.context-propagation=auto`. Con `LOG_FORMATO=` (vacío) el formato
vuelve a ser legible para desarrollo.

**Trazado distribuido (OpenTelemetry, bonus):**

```
POST /triage/pacientes ──► outbox (traceparent W3C guardado con el evento) ──► Kafka header traceparent
                                                                                     │
       asistente: observación "consumer" hija del request ◄──────────────────────────┤
       alertas:   observación "consumer" hija del request / del ciclo del monitor ◄──┘
```

- triage guarda el `traceparent` del span en curso en `outbox_eventos` (migración V2) y el relay lo publica como
  cabecera de Kafka.
- Cada ciclo del monitor de SLA es una observación propia, así los `paciente_escalado` también llevan traza.
- Los consumidores crean una observación con `ReceiverContext`; Micrometer extrae el `traceparent` y el span de
  consumo queda como hijo del original.
- Toda respuesta HTTP incluye `X-Trace-Id`: quien reporta un problema puede citar el id y encontrar todo lo que
  pasó en los tres servicios.
- El exportador OTLP se activa con `MANAGEMENT_OTLP_TRACING_ENDPOINT`. Sin él, los ids siguen en los logs.
- Verificado: el `traceId` del ciclo que escaló a un paciente en triage aparece en el log `ALERTA emitida` de
  alertas.

**Métricas (Micrometer → `/actuator/prometheus`, etiqueta `application`):**

| Métrica | Servicio | Uso |
|---|---|---|
| `triage_clasificacion_total{nivel}` | triage | Pacientes clasificados por nivel (requerida) |
| `triage_escalamientos_total{nivel}` | triage | Escalamientos por SLA vencido (requerida); se cuentan **después del commit** |
| `asistente_llm_latencia_seconds{resultado,modelo}` | asistente | Latencia del LLM con histograma (requerida); `resultado` = ok / error / timeout / circuito_abierto |
| `asistente_consultas_total{estado}` | asistente | Proporción RESPONDIDA / SIN_PROTOCOLO / RESPUESTA_DESCARTADA (vigila RN-05) |
| `alertas_emitidas_total{nivel}`, `alertas_notificaciones_total{canal,resultado}` | alertas | Alertas emitidas y salud del canal Teams |
| `resilience4j_circuitbreaker_*` | asistente, alertas | Estado de los circuitos `llm` y `teams` |
| `asistente_kafka_consumo_*`, `alertas_kafka_consumo_*` | consumidores | Latencia de procesamiento por mensaje |
| `http_server_requests_seconds_bucket` | todos | Latencia HTTP con histograma (p95/p99) |

Todos los niveles se registran al arrancar con valor 0, así una serie existe desde el inicio y los paneles no
quedan vacíos.

**Probes:** liveness = solo el estado interno (una dependencia caída nunca reinicia el pod). Readiness = el pod
recibe tráfico solo si responde lo imprescindible: triage → PostgreSQL (`r2dbc`), alertas → Redis. El asistente no
incluye Redis ni el LLM: su RAG funciona sin Redis, y un LLM caído se maneja con el modo degradado (ADR-0006).

### 3. BlockHound: el carácter reactivo, verificado por los tests

BlockHound se instala en la JVM de tests de los tres servicios. **Todos los tests de integración** (217 al
cerrar esta etapa) fallan si algo bloquea un hilo no bloqueante. Un test específico verifica que BlockHound
está activo.

Detectó **dos bloqueos reales**, ya corregidos:

1. **Script Lua leído del disco en el event loop de Lettuce.** `RedisScript.of(Resource)` carga el archivo de forma
   perezosa. Cuando Redis responde `NOSCRIPT` a `EVALSHA`, el ejecutor reactivo hace `EVAL` y lee el `.lua` del
   classpath en el hilo de I/O. Corrección: los scripts se cargan en memoria al arrancar (`ScriptsLua`).
2. **Conexión compartida de Lettuce abierta de forma síncrona en la primera petición.** `LettuceConnectionFactory`
   ejecuta `RedisClient.connect()` → `CompletableFuture.get()` en el hilo que la usa por primera vez (un hilo
   `parallel`). Corrección: inicialización anticipada (`setEagerInitialization(true)`), así la conexión se
   establece en el arranque.

Además, el test de SSE encontró un error de robustez: `ReactiveRedisTemplate.listenToChannel()` crea un contenedor
por suscripción y lo cierra al cancelarse; cuando se desconectaba el último tablero, los siguientes no podían
volver a suscribirse. Corrección: un único `ReactiveRedisMessageListenerContainer` de larga vida, con un test de
regresión.

Excepciones permitidas (justificadas en `SoporteBlockHound`): escritura de logs a consola, `UUID.randomUUID()`
(entropía del SO) y el `SharedLock` de Lettuce ≥ 6.4 (sección crítica en memoria, sin E/S).

## Consecuencias

- ✅ Ninguna credencial en el repositorio. Un servicio sin clave configurada falla al arrancar, en lugar de quedar
  abierto.
- ✅ Un `traceId` permite seguir un paciente desde la petición HTTP hasta la alerta en el tablero.
- ✅ El carácter no bloqueante se verifica en cada build.
- ⚠️ Las API keys son secretos de larga vida: en Azure se guardan en Key Vault, se montan como secretos de
  Kubernetes y se rotan con la lista separada por comas.
- ⚠️ El muestreo de trazas está al 100 % (`TRACING_MUESTREO=1.0`), adecuado para este volumen. Con tráfico alto se
  bajaría.
