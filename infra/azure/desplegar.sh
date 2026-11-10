#!/usr/bin/env bash
# =====================================================================================================================
# Despliega VidaCruz en Azure Kubernetes Service (AKS) de punta a punta.
#
#   Requisitos: az (con 'az login' hecho), docker, kubectl, openssl y un .env en la raíz (copia de .env.example).
#   Uso:        bash infra/azure/desplegar.sh
#
# Es idempotente: se puede volver a ejecutar para redesplegar (las claves generadas se reutilizan).
# Variables opcionales: UBICACION (eastus), GRUPO (vidacruz-rg), CLUSTER (vidacruz-aks), NODO (Standard_D4as_v4).
# =====================================================================================================================
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash en Windows: no reescribir rutas tipo /subscriptions/... en los argumentos de az

# Se trabaja desde la raíz con rutas RELATIVAS: con MSYS_NO_PATHCONV=1 los programas nativos de Windows (git,
# kubectl, docker) no entienden rutas estilo /d/... de Git Bash.
cd "$(dirname "$0")/../.."
ESTADO="infra/azure/.estado"     # nombres generados (no versionado)
CLAVES="infra/azure/.claves"     # API keys del entorno desplegado (no versionado; entregar por canal privado)

# eastus + Standard_D4as_v4: combinación verificada con cuota disponible en una suscripción gratuita (ADR-0009).
UBICACION="${UBICACION:-eastus}"
GRUPO="${GRUPO:-vidacruz-rg}"
CLUSTER="${CLUSTER:-vidacruz-aks}"
NODO="${NODO:-Standard_D4as_v4}"

paso() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
falla() { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# --- 0) Verificaciones -------------------------------------------------------------------------------------------
paso "Verificando herramientas y sesión de Azure"
for herramienta in az docker kubectl openssl git; do
  command -v "$herramienta" >/dev/null || falla "Falta '$herramienta' en el PATH"
done
az account show >/dev/null 2>&1 || falla "No hay sesión de Azure: ejecute 'az login'"
[ -f .env ] || falla "Falta .env en la raíz (copie .env.example)"
docker info >/dev/null 2>&1 || falla "Docker no responde"
echo "Suscripción: $(az account show --query name -o tsv)"

leer_env() { grep -E "^$1=" .env | head -1 | cut -d= -f2- | tr -d '\r' || true; }

# Nombre del registro: global y único en Azure, se genera una vez y se recuerda.
[ -f "$ESTADO" ] && . "$ESTADO"
if [ -z "${ACR:-}" ]; then
  ACR="vidacruz$(openssl rand -hex 3)"
  echo "ACR=$ACR" > "$ESTADO"
fi
# Commit + marca de tiempo si hay cambios sin commit: una etiqueta nueva obliga al nodo a descargar la imagen.
TAG="$(git rev-parse --short HEAD)"
git diff --quiet HEAD -- . || TAG="$TAG-$(date +%Y%m%d%H%M)"
echo "Grupo=$GRUPO  Ubicación=$UBICACION  Clúster=$CLUSTER  Nodo=$NODO  Registro=$ACR  Tag=$TAG"

# --- 1) Proveedores de recursos (suscripciones nuevas los traen sin registrar) -----------------------------------
paso "Registrando proveedores de recursos"
for ns in Microsoft.ContainerService Microsoft.ContainerRegistry Microsoft.Network Microsoft.Compute Microsoft.Storage; do
  az provider register --namespace "$ns" --wait >/dev/null
done

# --- 2) Infraestructura -------------------------------------------------------------------------------------------
paso "Grupo de recursos"
az group create --name "$GRUPO" --location "$UBICACION" --output none

paso "Azure Container Registry ($ACR, Basic)"
az acr show --name "$ACR" --output none 2>/dev/null \
  || az acr create --resource-group "$GRUPO" --name "$ACR" --sku Basic --output none

paso "Clúster AKS (1 nodo $NODO, plan Free, ingress NGINX administrado). Puede tardar ~10 min la primera vez"
if ! az aks show --resource-group "$GRUPO" --name "$CLUSTER" --output none 2>/dev/null; then
  az aks create \
    --resource-group "$GRUPO" --name "$CLUSTER" --location "$UBICACION" \
    --tier free --node-count 1 --node-vm-size "$NODO" \
    --enable-app-routing \
    --attach-acr "$ACR" \
    --generate-ssh-keys \
    --output none
fi
az aks get-credentials --resource-group "$GRUPO" --name "$CLUSTER" --overwrite-existing --output none
kubectl get nodes -o wide

# --- 3) Imágenes --------------------------------------------------------------------------------------------------
paso "Construyendo y publicando imágenes en $ACR.azurecr.io (tag $TAG)"
az acr login --name "$ACR"
docker compose build triage asistente alertas
for servicio in triage asistente alertas; do
  origen="vidacruz/$servicio-service:local"
  destino="$ACR.azurecr.io/vidacruz/$servicio-service:$TAG"
  docker tag "$origen" "$destino"
  docker push "$destino"
done

# --- 4) Secretos (nunca en el repositorio) ------------------------------------------------------------------------
paso "Secretos del entorno"
if [ ! -f "$CLAVES" ]; then
  # Entorno público: claves aleatorias fuertes, NO las de ejemplo de .env.example.
  {
    echo "TRIAGE_API_KEY=$(openssl rand -hex 24)"
    echo "ASISTENTE_API_KEY=$(openssl rand -hex 24)"
    echo "ALERTAS_API_KEY=$(openssl rand -hex 24)"
    echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)"
  } > "$CLAVES"
  chmod 600 "$CLAVES" 2>/dev/null || true
  echo "Claves nuevas generadas en $CLAVES"
fi
leer_clave() { grep -E "^$1=" "$CLAVES" | cut -d= -f2-; }
kubectl apply -f k8s/namespace.yaml >/dev/null
kubectl create secret generic vidacruz-secretos --namespace vidacruz \
  --from-literal=TRIAGE_API_KEY="$(leer_clave TRIAGE_API_KEY)" \
  --from-literal=ASISTENTE_API_KEY="$(leer_clave ASISTENTE_API_KEY)" \
  --from-literal=ALERTAS_API_KEY="$(leer_clave ALERTAS_API_KEY)" \
  --from-literal=POSTGRES_PASSWORD="$(leer_clave POSTGRES_PASSWORD)" \
  --from-literal=LLM_API_KEY="$(leer_env LLM_API_KEY | sed 's/^$/sin-configurar/')" \
  --from-literal=TEAMS_WEBHOOK_URL="$(leer_env TEAMS_WEBHOOK_URL)" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
echo "Secret vidacruz-secretos aplicado (valores no impresos)"

# --- 5) Manifiestos -----------------------------------------------------------------------------------------------
paso "Aplicando manifiestos (kustomize) con las imágenes de $ACR"
kubectl kustomize k8s \
  | sed -E "s#image: vidacruz/([a-z]+-service):local#image: $ACR.azurecr.io/vidacruz/\1:$TAG#" \
  | kubectl apply -f -

paso "Esperando a que todo esté listo"
for sts in postgres kafka redis; do kubectl -n vidacruz rollout status statefulset/"$sts" --timeout=10m; done
kubectl -n vidacruz wait --for=condition=complete job/kafka-crear-topic --timeout=10m || true
for dep in triage alertas asistente; do kubectl -n vidacruz rollout status deployment/"$dep" --timeout=15m; done

paso "IP pública del ingress"
IP=""
for _ in $(seq 1 60); do
  IP="$(kubectl -n vidacruz get ingress vidacruz -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"
  [ -n "$IP" ] && break
  sleep 5
done
[ -n "$IP" ] || falla "El ingress no obtuvo IP pública"
echo "URL=http://$IP" >> "$ESTADO"

# --- 6) Prueba de humo contra la URL pública ----------------------------------------------------------------------
paso "Prueba de humo contra http://$IP"
TK="$(leer_clave TRIAGE_API_KEY)"; AK="$(leer_clave ASISTENTE_API_KEY)"
printf 'Sin API key           -> HTTP %s (esperado 401)\n' \
  "$(curl -s -o /dev/null -w '%{http_code}' "http://$IP/triage/reportes/operacion?desde=2026-01-01&hasta=2026-01-02")"
printf 'Registro de paciente  -> HTTP %s (esperado 201)\n' \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://$IP/triage/pacientes" -H "X-API-Key: $TK" \
     -H 'Content-Type: application/json' \
     -d '{"nombreCompleto":"Paciente Humo","edad":58,"frecuenciaCardiaca":130,"presionSistolica":180,"presionDiastolica":110,"spo2":97,"temperatura":36.8,"dolor":8,"gcs":15,"quejaPrincipal":"Dolor toracico"}')"
sleep 3
printf 'Estado de urgencias   -> %s\n' "$(curl -s "http://$IP/asistente/estado-urgencias" -H "X-API-Key: $AK")"

cat <<FIN

=====================================================================================================
 VidaCruz desplegado en Azure
   URL base:   http://$IP
   Triage:     http://$IP/triage/pacientes
   Asistente:  http://$IP/asistente/consulta · http://$IP/asistente/estado-urgencias
   Alertas:    http://$IP/alertas · http://$IP/alertas/stream?api_key=<ALERTAS_API_KEY>
   API keys:   $CLAVES   (no versionado; compártalas por un canal privado)

 Para ahorrar crédito:  bash infra/azure/apagar.sh   ·   bash infra/azure/encender.sh
 Para eliminar todo:    bash infra/azure/eliminar.sh
=====================================================================================================
FIN
