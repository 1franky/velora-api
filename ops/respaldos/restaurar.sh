#!/usr/bin/env bash
# Restaura un respaldo de Velora (ADR-027) en una base de datos VACÍA y un volumen de originales vacío.
#
#   ops/respaldos/restaurar.sh [instantánea-db] [instantánea-originales]   (por omisión, las más recientes)
#
# Usa las mismas variables RESTIC_* que respaldar.sh, más:
#   VELORA_RESTORE_PG_CONTAINER (velora-postgres), VELORA_DB_NAME (velora), VELORA_DB_USERNAME (velora)
#   VELORA_RESTORE_ORIGINALS_VOLUME (velora-originals)
# La API debe estar detenida mientras se restaura. Ver el runbook en ops/respaldos/README.md.
set -Eeuo pipefail

PG_CONTAINER="${VELORA_RESTORE_PG_CONTAINER:-velora-postgres}"
DB_NAME="${VELORA_DB_NAME:-velora}"
DB_USER="${VELORA_DB_USERNAME:-velora}"
ORIGINALS_VOLUME="${VELORA_RESTORE_ORIGINALS_VOLUME:-velora-originals}"
HELPER_IMAGE="${VELORA_HELPER_IMAGE:-alpine:3.22}"
DB_SNAPSHOT="${1:-latest}"
FILES_SNAPSHOT="${2:-latest}"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

# Por seguridad, no se mezcla con datos existentes: la base destino no debe tener actividades.
existing=$(docker exec "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc \
  "select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'activities'")
if [[ "$existing" != "0" ]]; then
  echo "La base ${DB_NAME} en ${PG_CONTAINER} ya tiene el esquema de Velora: restaura en una base vacía." >&2
  exit 1
fi

log "Restaurando la base desde la instantánea ${DB_SNAPSHOT}"
# --exit-on-error: una restauración a medias no cuenta como restaurada.
restic dump --tag velora,db "$DB_SNAPSHOT" /velora-db.dump \
  | docker exec -i "$PG_CONTAINER" pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner --exit-on-error

log "Restaurando los originales desde la instantánea ${FILES_SNAPSHOT}"
restic dump --tag velora,originales "$FILES_SNAPSHOT" /velora-originales.tar \
  | docker run --rm -i -v "${ORIGINALS_VOLUME}:/data" "$HELPER_IMAGE" tar -C /data -xf -

log "Restauración completa. Arranca la API y revisa /healthz y tu historial."
