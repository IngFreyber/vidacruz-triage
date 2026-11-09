# ADR-0001: Descomposición en tres microservicios y arquitectura hexagonal

- **Estado:** Aceptado
- **Fecha:** 2026-11-09

## Contexto

El enunciado exige al menos dos microservicios reactivos (Triage y Asistente Clínico) y deja
abierta una descomposición adicional, siempre que esté justificada. Además:

- El servicio de triaje **nunca** debe depender de la disponibilidad del asistente (ni del LLM).
- RF-06 (alertas de escalamiento al equipo médico) es un requerimiento abierto en canal y tecnología.
- La arquitectura interna es de libre elección y se evalúa como indicador de diseño.

## Decisión

1. **Tres microservicios** en un monorepo Maven multi-módulo:
   - `triage-service`: núcleo transaccional clínico (registro, clasificación, estados, SLA,
     escalamiento, auditoría, reportes). Dueño único de PostgreSQL.
   - `asistente-service`: RAG sobre protocolos y vista materializada del estado de urgencias,
     construida **solo** a partir de `triaje.eventos`.
   - `alertas-service`: notificación al equipo médico ante `paciente_escalado` (RF-06).
2. **Comunicación exclusivamente asíncrona** entre servicios vía Kafka (`triaje.eventos`).
   Ningún servicio llama por HTTP a otro.
3. **Arquitectura hexagonal (puertos y adaptadores)** dentro de cada servicio:
   `domain` (reglas puras, sin Spring) → `application` (casos de uso + puertos) →
   `infrastructure` (adaptadores web, R2DBC, Kafka, Redis, LLM).
4. **Sin librería compartida de contratos**: cada servicio define sus propios DTO de evento; el
   contrato JSON se documenta (ADR de formato de mensajes). Se evita el acoplamiento de despliegue.

## Alternativas consideradas

| Alternativa | Pros | Contras |
|---|---|---|
| 2 servicios, alertas dentro de triage | Menos piezas | Triage acoplado a canales externos (Teams, SSE); un webhook lento afectaría el núcleo clínico |
| 2 servicios, alertas dentro del asistente | Menos piezas | Mezcla responsabilidades: si el LLM degrada el servicio, las alertas críticas también se ven afectadas |
| **3 servicios (elegida)** | Aislamiento de fallos, escalado y SLA independientes por responsabilidad | Una imagen y un despliegue más |
| Llamadas HTTP síncronas entre servicios | Simple de razonar | Acoplamiento temporal; viola "triage nunca depende del asistente" |
| Módulo compartido `events-contract` | Tipado único | Acopla versiones y despliegues de todos los servicios |

## Consecuencias

- ✅ Una caída del LLM o del canal de alertas no afecta el registro ni la clasificación de pacientes.
- ✅ El dominio de triaje es testeable sin infraestructura (tests unitarios puros).
- ⚠️ Consistencia eventual: la vista de urgencias y las alertas pueden ir milisegundos/segundos
  por detrás del estado en PostgreSQL. Es aceptable para un tablero y una notificación.
- ⚠️ Los contratos de eventos duplicados entre servicios requieren disciplina: se documentan
  y se cubren con tests de serialización.
