#!/usr/bin/env bash
# Detiene el clúster AKS (deja de facturar el nodo; se conservan discos, datos y configuración).
set -euo pipefail
export MSYS_NO_PATHCONV=1
GRUPO="${GRUPO:-vidacruz-rg}"
CLUSTER="${CLUSTER:-vidacruz-aks}"
az aks stop --resource-group "$GRUPO" --name "$CLUSTER"
echo "Clúster detenido. Para volver a levantarlo: bash infra/azure/encender.sh"
