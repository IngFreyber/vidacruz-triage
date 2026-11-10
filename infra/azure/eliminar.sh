#!/usr/bin/env bash
# Elimina TODOS los recursos de VidaCruz en Azure (grupo de recursos completo). Irreversible.
set -euo pipefail
export MSYS_NO_PATHCONV=1
GRUPO="${GRUPO:-vidacruz-rg}"
read -r -p "Esto elimina el grupo '$GRUPO' y TODO su contenido (clúster, registro, discos). Escriba el nombre del grupo para confirmar: " respuesta
[ "$respuesta" = "$GRUPO" ] || { echo "Cancelado."; exit 1; }
az group delete --name "$GRUPO" --yes --no-wait
rm -f "$(dirname "$0")/.estado"
echo "Eliminación en curso (unos minutos). Las claves en infra/azure/.claves se conservan por si redespliega."
