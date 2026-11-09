# ADR-0006: Modo degradado del asistente ante fallas del proveedor de LLM

- **Estado:** Aceptado
- **Fecha:** 2026-11-09

## Contexto

El proveedor de LLM es una dependencia externa con cuotas, latencia variable y caídas. Al evaluar modelos,
`gemini-3.8-flash` dejó una conexión colgada **332 s** sin responder. El enunciado exige un circuit breaker con
fallback explícito (503 con mensaje claro) y que el triaje nunca dependa del asistente.

## Decisión

1. **La llamada bloqueante de Spring AI corre en `Schedulers.boundedElastic()`**, nunca en el event loop de Netty.
2. **Tres límites de tiempo en capas**: timeout HTTP del cliente (`connect 5s` / `read 20s`) → **TimeLimiter**
   de Resilience4j (25 s) → **CircuitBreaker** (`llm`). El orden es `timeLimiter` dentro de `circuitBreaker`,
   así los timeouts cuentan como fallas del circuito.
3. **Circuit breaker**: ventana de 10 llamadas, abre con ≥ 50 % de fallas (mínimo 4 llamadas) o ≥ 80 % de
   llamadas lentas (> 15 s). Permanece abierto 30 s y luego pasa a semiabierto con 2 llamadas de prueba.
   Mientras está abierto, la respuesta es inmediata (~20 ms) y no se llama al proveedor.
4. **Sin reintentos ocultos**: `spring.ai.retry.max-attempts=1`. El retry por defecto de Spring AI (10 intentos
   con backoff) habría convertido cada caída en minutos de espera por petición.
5. **Fallback explícito**: toda falla del proveedor se traduce a `LlmNoDisponibleException` → **HTTP 503**
   (RFC 7807) con el motivo (error del proveedor, timeout o circuito abierto), `Retry-After: 30` y una
   indicación operativa: consultar el protocolo impreso; el triaje sigue operando.
6. **Degradación parcial, no total**: las preguntas sin protocolo relevante se responden sin LLM (ADR-0005), así
   que siguen funcionando con el proveedor caído. `GET /asistente/estado-urgencias` no usa el LLM.
7. **El estado del circuito es observable pero no mata el pod**: el health indicator del circuito se publica
   con `allow-health-indicator-to-fail: false`. Un LLM caído no hace que Kubernetes reinicie el servicio, que
   no ganaría nada.
8. **Sin API key el servicio arranca igual** y responde 503 en las consultas que necesitan LLM. La falta de una
   credencial externa no impide operar el resto.

## Verificación

Con una API key inválida contra Gemini real: intentos 1–4 → 503 "error del proveedor" (~150 ms); intento 5 →
503 "circuito abierto" en **20 ms**, sin llamar al proveedor; la pregunta de diabetes → 200 "sin protocolo";
`/actuator/health` → UP. Tests automáticos: LLM que lanza error, LLM que tarda más que el TimeLimiter, apertura
del circuito tras 4 fallas (verificando 0 llamadas al proveedor después) y "sin protocolo" con el LLM caído.

## Alternativas consideradas

| Alternativa | Por qué se descartó |
|---|---|
| Respuesta de respaldo generada con reglas o plantillas | Riesgo clínico: contenido no sustentado. El enunciado pide un 503 explícito |
| Proveedor LLM secundario (failover) | Duplica cuentas, claves y prompts que validar; queda como evolución (bastaría un segundo `ChatModel` en el fallback) |
| Anotaciones `@CircuitBreaker` | En pipelines reactivos los operadores `transformDeferred` son explícitos, se componen con Reactor y no dependen de proxies AOP |
| Caché de respuestas | Las preguntas son texto libre (baja tasa de acierto) y una respuesta clínica cacheada podría quedar obsoleta al cambiar el corpus |

## Consecuencias

- ✅ Una caída o lentitud del LLM no consume hilos del event loop ni bloquea al resto del servicio.
- ✅ Durante una caída prolongada las respuestas son inmediatas: no se acumulan peticiones colgadas.
- ⚠️ Un hilo de `boundedElastic` puede seguir esperando hasta el read timeout (20 s) tras el corte del
  TimeLimiter. Está acotado por el pool elástico.
