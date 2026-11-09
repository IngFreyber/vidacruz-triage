# ADR-0003: Monitor de SLA por scheduler reactivo y escalamiento idempotente

- **Estado:** Aceptado
- **Fecha:** 2026-11-09

## Contexto

RF-03 pide un proceso de fondo ("scheduler, consumidor o equivalente — tu decisión") que escale a los
pacientes en `REGISTRADO` que superen su SLA. RN-03 exige escalar **una única vez** (sin segundo evento ni
auditoría duplicada) y **nunca** a pacientes `EN_CONSULTA` o `ALTA`. Tanto el intervalo como los SLA deben
ser configurables para poder probar sin esperar decenas de minutos.

## Decisión

1. **Scheduler reactivo dentro de `triage-service`** (`MonitorSla`):
   `Flux.interval(intervalo)` → `onBackpressureDrop` → `concatMap(evaluar)`. Las evaluaciones nunca se
   solapan y un error se registra sin detener el monitor.
2. **Idempotencia en tres capas**, todas dentro de una transacción por lote:
   - **Consulta:** `WHERE estado='REGISTRADO' AND escalado=false AND fecha_registro + sla < ahora`
     con `FOR UPDATE SKIP LOCKED`. Dos réplicas (o dos ciclos) nunca procesan al mismo paciente.
   - **Dominio:** `Paciente.escalarSiVencido` vuelve a validar estado, flag y vencimiento.
   - **Escritura:** `UPDATE … WHERE version = :leida`.
   Flag `escalado`, entrada de auditoría y evento `paciente_escalado` (outbox) se confirman juntos.
3. **El SLA se fija por paciente al registrarlo** (`sla_segundos`). Cambiar la configuración afecta solo a los
   nuevos registros, nunca retroactivamente a quienes ya esperan.
4. **Configuración por entorno:** `TRIAGE_SLA_<NIVEL>` (formato Duration: `30s`, `5m`) y
   `TRIAGE_ESCALAMIENTO_INTERVALO`. Con `TRIAGE_SLA_CRITICO=20s` y `TRIAGE_ESCALAMIENTO_INTERVALO=3s` el
   escalamiento se observa en ~20 s (verificado contra el docker-compose).

## Alternativas consideradas

| Alternativa | Por qué se descartó |
|---|---|
| Consumidor Kafka de `paciente_registrado` con temporizador por paciente | El vencimiento de un SLA es la *ausencia* de un evento en el tiempo. Los temporizadores en memoria se pierden al reiniciar o escalar el pod y exigen reconstruir estado |
| Mensajes con retraso / cola con TTL | Kafka no tiene entrega diferida nativa; agregaría otro broker |
| `@Scheduled` de Spring | Corre en un hilo bloqueante del scheduler de Spring y no aporta backpressure ni composición con Reactor |
| `pg_cron` u otro job en la base de datos | Mueve lógica de negocio (RN-03, auditoría, outbox) fuera del dominio y complica los tests |
| ShedLock / elección de líder | Innecesario: `SKIP LOCKED` ya hace segura la ejecución concurrente sin coordinador |

## Consecuencias

- ✅ Precisión de escalamiento ≤ intervalo configurado (30 s por defecto, que es holgado frente al SLA mínimo de 5 min).
- ✅ Funciona con N réplicas sin coordinación adicional (probado con 3 evaluaciones concurrentes: 1 escalamiento).
- ✅ Tras un reinicio no se pierde nada: el estado vive en PostgreSQL, no en memoria.
- ⚠️ Cada ciclo hace una consulta; el índice parcial `ix_pacientes_pendientes_sla` la mantiene barata porque
  solo indexa pacientes en espera no escalados.
- ⚠️ El API expone `slaMinutos` redondeado hacia arriba (un SLA de 20 s de prueba se informa como 1 min);
  `vencimientoSla` da el instante exacto.
