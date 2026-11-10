# ADR-0007: Alertas de escalamiento al equipo médico (RF-06)

- **Estado:** Aceptado
- **Fecha:** 2026-11-09

## Contexto

RF-06 pide alertar al equipo médico cuando un paciente es escalado, y deja abiertos el canal, el formato y la
tecnología. Partí de estas preguntas:

| Pregunta | Supuesto |
|---|---|
| ¿Quién recibe la alerta? | El equipo médico **de turno en la sala de urgencias**, mirando un tablero (pantalla de la estación de enfermería o del área médica). No un médico en particular: el enunciado no modela profesionales ni asignaciones |
| ¿Qué tan rápido? | Segundos. Un paciente CRITICO tiene SLA de 5 min y ya lo superó. Correo o SMS con minutos de demora no sirven |
| ¿Qué hace el médico con la alerta? | La **toma** ("yo lo atiendo"). Así el resto del equipo sabe que alguien se hizo cargo y no hay respuestas duplicadas ni alertas ignoradas por suponer que otro la atendió |
| ¿Qué pasa si nadie mira el tablero? | Hace falta un segundo canal fuera del sistema: VidaCruz, como grupo de salud, probablemente usa Microsoft 365 (y el despliegue objetivo es Azure) |
| ¿Se necesita trazabilidad? | Sí: el enunciado critica la falta de trazabilidad. Toda alerta queda en un historial con quién la tomó y cuándo |

## Decisión

Un **tercer microservicio, `alertas-service`** (justificado en ADR-0001), que consume `paciente_escalado` de
`triaje.eventos` y entrega la alerta por dos canales:

### Canal 1 (principal): Server-Sent Events para tableros

`GET /alertas/stream` (`text/event-stream`):

- Al conectar envía las alertas **pendientes** (un tablero recién abierto ve el estado actual) y luego, en tiempo
  real, `event: alerta` (nueva) y `event: alerta_atendida` (alguien la tomó).
- Un comentario `:keep-alive` cada 15 s evita que proxies y balanceadores (Azure Application Gateway / NGINX)
  cierren la conexión inactiva.
- SSE en lugar de WebSocket: el flujo es unidireccional (servidor → tablero), funciona sobre HTTP estándar, el
  navegador reconecta solo con `EventSource` y retoma con `Last-Event-ID`, y WebFlux lo soporta de forma nativa.
- **Varias réplicas:** el consumer group de Kafka entrega cada escalamiento a una sola instancia, pero el
  tablero puede estar conectado a otra. La instancia que procesa publica en **Redis Pub/Sub**
  (`alertas:eventos`), y cada réplica reenvía a sus conexiones SSE locales (una sola suscripción compartida por
  instancia).
- **Backpressure:** un buffer acotado (256) por conexión con `DROP_OLDEST`. Un tablero lento pierde los eventos
  más viejos en lugar de frenar a los demás; el historial sigue disponible por REST.

### Canal 2 (complementario): Microsoft Teams

- Webhook de **Workflows** (Power Automate) con una **Adaptive Card 1.4**: título con el nivel, mensaje y datos
  (paciente, nivel, espera vs. SLA, motivo, hora, id). Los conectores "Incoming Webhook" de Office 365 fueron
  retirados por Microsoft, así que se usa el formato que acepta Workflows.
- **Opcional:** se activa con `TEAMS_WEBHOOK_URL`. La URL contiene un token secreto: llega solo por variable de
  entorno y nunca se registra en logs (solo el host).
- **Resiliencia:** timeout de 5 s por intento, 3 intentos con backoff exponencial y circuit breaker `teams`. Si
  Teams falla, la alerta **igual** queda en el historial y sale por SSE, y el consumidor sigue con el siguiente
  evento.

### Historial y atención (acknowledge)

| Método | Ruta | Uso |
|---|---|---|
| `GET` | `/alertas?estado=PENDIENTE&limite=50` | Historial, más recientes primero |
| `GET` | `/alertas/{id}` | Detalle |
| `POST` | `/alertas/{id}/atencion` `{"atendidaPor":"Dra. Salas"}` | El profesional toma la alerta |

- **Idempotencia:** el id de la alerta es el `eventId` del escalamiento. Con un script Lua (`SET` si no existe +
  índices) una reentrega no genera una segunda alerta ni un segundo aviso a Teams.
- **Atención atómica (Lua):** ante dos confirmaciones simultáneas gana la primera y queda registrado quién fue;
  la segunda recibe 200 con el estado vigente.
- **Retención:** 30 días (TTL por alerta + poda de los índices).

### Formato de la alerta

```json
{"id":"d2238080-…","pacienteId":"e8e11878-…","nombrePaciente":"Carlos Vega","quejaPrincipal":"Inconsciente",
 "nivel":"CRITICO","slaMinutos":5,"minutosEspera":7,"fechaRegistro":"…","escaladoEn":"…",
 "mensaje":"Paciente CRITICO escalado: Carlos Vega lleva 7 min sin entrar a consulta (SLA 5 min). Motivo: Inconsciente.",
 "estado":"PENDIENTE"}
```

La alerta se arma solo con el evento (*event-carried state transfer*), sin llamar a la API de triaje.

## Alternativas consideradas

| Alternativa | Por qué se descartó |
|---|---|
| Correo electrónico | Latencia y baja visibilidad en una urgencia; sin forma de confirmar la atención |
| SMS / push móvil (Twilio, Azure Notification Hubs) | Requiere directorio de profesionales y turnos que el enunciado no modela; costo por mensaje. Queda como evolución (otro `NotificadorExterno`) |
| WebSocket | Bidireccional e innecesario aquí; más complejo detrás de proxies; sin reconexión nativa del navegador |
| Polling del tablero a `GET /alertas` | Latencia igual al intervalo y carga constante; SSE entrega en ~ms |
| Alertas dentro de triage o del asistente | Ver ADR-0001: acoplaría el núcleo clínico o la disponibilidad del LLM a canales externos |
| Kafka con un consumer group por réplica (broadcast) | Grupos efímeros al reiniciar pods, re-lecturas y offsets huérfanos; Redis Pub/Sub ya está en el stack |
| Re-alertar escalamientos históricos al desplegar | Ruido: un consumer group nuevo empieza en `latest` (configurable con `ALERTAS_OFFSET_INICIAL`). El historial de lo ya escalado está en triage |

## Consecuencias

- ✅ Latencia de punta a punta medida: el vencimiento del SLA llega al tablero en ≤ el intervalo del monitor
  (prueba local: SLA 20 s → alerta SSE a los ~21 s del registro).
- ✅ Cada alerta tiene responsable y hora de atención: trazabilidad completa del escalamiento.
- ✅ Un canal externo caído no afecta al registro ni a la difusión SSE.
- ⚠️ Si la instancia cae **entre** guardar la alerta y enviarla a Teams, la reentrega del evento no reenvía a Teams
  (la alerta ya existe). El tablero SSE y el historial sí la muestran. Mitigación futura: un estado
  `notificadaExterno` con reintentos programados.
- ⚠️ Redis Pub/Sub es *fire-and-forget*: un tablero desconectado en ese instante no recibe el evento en vivo, pero
  al reconectar recibe las pendientes.
- ⚠️ `EventSource` del navegador no permite cabeceras personalizadas. La autenticación del stream (Etapa 9)
  admite la API key también como parámetro de consulta, solo para esa ruta.
