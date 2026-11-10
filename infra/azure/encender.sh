#!/usr/bin/env bash
# Vuelve a encender el clúster AKS detenido con apagar.sh. Los pods y datos se restauran solos.
set -euo pipefail
export MSYS_NO_PATHCONV=1
GRUPO="${GRUPO:-vidacruz-rg}"
CLUSTER="${CLUSTER:-vidacruz-aks}"
az aks start --resource-group "$GRUPO" --name "$CLUSTER"
az aks get-credentials --resource-group "$GRUPO" --name "$CLUSTER" --overwrite-existing --output none
kubectl -n vidacruz rollout status deployment/asistente --timeout=15m
echo "URL: http://$(kubectl -n vidacruz get ingress vidacruz -o jsonpath='{.status.loadBalancer.ingress[0].ip}')"
