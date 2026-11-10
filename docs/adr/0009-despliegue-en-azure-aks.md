# ADR-0009: Despliegue en Azure Kubernetes Service (AKS)

- **Estado:** Aceptado
- **Fecha:** 2026-11-10

## Contexto

El enunciado exige manifiestos de Kubernetes (Deployment, Service, probes) con Azure como objetivo (AKS o Azure
Container Apps) y una URL pública con las APIs accesibles. El despliegue se hace en una **suscripción gratuita**
(USD 200 de crédito, cuota de **4 vCPU por región**).

## Decisión

### 1. AKS con todo dentro del clúster

| Componente | En este despliegue | En producción (recomendado) |
|---|---|---|
| Cómputo | AKS, plan Free, **1 nodo `Standard_D4as_v4`** (4 vCPU / 16 GB) en **East US** | 3+ nodos en zonas de disponibilidad, plan Standard (SLA del plano de control) |
| PostgreSQL 16 | StatefulSet + disco administrado (`managed-csi`, 5 GiB) | Azure Database for PostgreSQL Flexible Server (HA, backups, PITR) |
| Kafka 3.9 (KRaft) | StatefulSet, 1 broker, retención de 7 días | Azure Event Hubs (API Kafka) o un clúster de 3 brokers |
| Redis 7 | StatefulSet con AOF | Azure Cache for Redis Standard (sin modo cluster, ADR-0004) |
| Ingress | Complemento *application routing* (NGINX administrado), una IP pública | Igual + TLS (cert-manager / Key Vault) + WAF (Application Gateway / Front Door) |
| Imágenes | Azure Container Registry Basic, adjunto al clúster (sin credenciales en el clúster) | ACR Standard/Premium con escaneo de vulnerabilidades |
| Secretos | Secret de Kubernetes creado por el script; API keys aleatorias, nunca versionadas | Azure Key Vault + Secrets Store CSI Driver |

**¿Por qué AKS y no Container Apps?** El enunciado pide manifiestos de Kubernetes (Deployment, Service, probes);
AKS los usa tal cual y son portables a cualquier Kubernetes.

**¿Por qué todo en el clúster?** Costo y simplicidad para una prueba: unos USD 4.6/día con el nodo encendido y
casi nada apagado (`apagar.sh`). Los servicios gestionados equivalentes sumarían USD 2–4/día más y varias piezas
de red y credenciales que configurar. Las aplicaciones no cambian para pasar a gestionados: solo cambian
`DB_HOST`, `KAFKA_BOOTSTRAP_SERVERS` y `REDIS_HOST/REDIS_SSL`.

**Región y tamaño, verificados contra la cuota real de la cuenta:** en `eastus2`, los tamaños de 4 vCPU estaban
ocultos o con `NotAvailableForSubscription`. `Standard_D4as_v4` en `eastus` estaba disponible y la familia
DASv4 tenía cuota justa (4/4).

### 2. Manifiestos (`k8s/`, Kustomize)

- **3 Deployments**: triage **×2**, alertas **×2** y asistente ×1. Las réplicas demuestran en infraestructura real
  el escalamiento idempotente con `SKIP LOCKED` (ADR-0003) y la difusión SSE entre réplicas (ADR-0007).
- **Probes:** `startupProbe` (hasta 3–5 min para arranques lentos: Flyway, indexación ONNX), `readinessProbe` y
  `livenessProbe` sobre los grupos de actuator (ADR-0008).
- **Seguridad del pod:** `runAsNonRoot` con UID numérico 10001, sistema de archivos raíz de solo lectura (`/tmp` en
  `emptyDir`), sin capabilities, `seccompProfile: RuntimeDefault`, sin escalada de privilegios.
- **Recursos:** requests y limits por pod; `RollingUpdate` con `maxUnavailable: 0`; `preStop` para drenar
  conexiones del ingress.
- **Dependencias:** `initContainer` que espera PostgreSQL/Kafka/Redis antes de arrancar la aplicación.
- **Ingress:** solo `/triage`, `/asistente` y `/alertas`. `/actuator` **no** es accesible desde internet. Sin
  buffering y con timeout de lectura largo para SSE.

### 3. Despliegue reproducible (`infra/azure/`)

`desplegar.sh` es idempotente: registra proveedores, crea grupo, ACR y AKS, construye y publica imágenes
(etiqueta = commit + marca de tiempo si hay cambios sin confirmar), genera API keys aleatorias en
`infra/azure/.claves` (ignorado por git), crea el Secret, aplica los manifiestos, espera a que todo esté listo y
hace una prueba de humo contra la URL pública. `apagar.sh` / `encender.sh` detienen y reanudan el nodo;
`eliminar.sh` borra todo con confirmación explícita.

## Problemas reales encontrados al desplegar (y corregidos)

Ninguno aparecía en Docker Compose; los cuatro se reprodujeron y corrigieron en AKS:

| Síntoma | Causa | Corrección |
|---|---|---|
| Kafka en `CrashLoopBackOff` | Los discos administrados (ext4) traen `lost+found` en la raíz del volumen y Kafka rechaza toda carpeta que no sea una partición | `KAFKA_LOG_DIRS` en un subdirectorio del volumen |
| `CreateContainerConfigError` en los 3 servicios | `runAsNonRoot` exige un UID **numérico**; las imágenes declaraban `USER vidacruz` | `USER 10001:10001` en los Dockerfiles |
| asistente y alertas no arrancaban: *"Failed to bind spring.data.redis.port"* | Kubernetes inyecta `REDIS_PORT=tcp://10.0.x.x:6379` por el Service `redis` (*service links*) y pisa la variable de la aplicación | `enableServiceLinks: false` (el descubrimiento es por DNS) |
| asistente `OOMKilled` | ONNX Runtime mantiene el modelo (~460 MB) en memoria **nativa**; con el heap al 75 % del límite no quedaba espacio | Heap al 50 % (`MaxRAMPercentage=50`) y límite de 3.5 GiB |
| El stream SSE tardaba 15 s en abrir a través del ingress | WebFlux envía cabeceras con el primer elemento; sin alertas pendientes, el primero era el keep-alive | Comentario `:conectado` inmediato (test de regresión) |

## Verificación (contra la URL pública)

401 sin API key · `/actuator` → 404 desde internet · registro E6 → CRITICO · vista de urgencias reconstruida desde
Kafka · RAG con Gemini real: ejemplo 1 → PROTO-001 / Medidas iniciales, ejemplo 3 → `SIN_PROTOCOLO` · reporte
de operación · SSE con primer byte en ~0.2 s · **dos pacientes escalados al vencer su SLA de 5 min: cada uno una
sola vez, con 2 réplicas de triage, y exactamente una alerta por paciente con 2 réplicas de alertas**.

## Consecuencias

- ✅ Despliegue reproducible con un comando y sin secretos en el repositorio.
- ✅ Las garantías de concurrencia se comprobaron con réplicas reales.
- ⚠️ Un solo nodo: no hay alta disponibilidad (aceptado para la prueba). Con más cuota se agrega un *node pool*
  de 3 nodos sin cambiar manifiestos.
- ⚠️ HTTP sin TLS sobre una IP pública: las API keys viajan en claro. Para producción: dominio, TLS con
  cert-manager/Let's Encrypt o un certificado en Key Vault, y WAF.
- ⚠️ Kafka y PostgreSQL en el clúster no tienen backups gestionados; en producción se usan los servicios PaaS.
