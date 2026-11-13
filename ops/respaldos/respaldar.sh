#!/usr/bin/env bash
# Respaldo diario de Velora (ADR-027): base de datos y archivos originales, cifrados con restic, fuera del VPS.
#
# Corre como un usuario del grupo docker (sin root): la base sale de `pg_dump` dentro del contenedor de PostgreSQL y
# los originales de un contenedor desechable que monta el volumen en solo lectura. Nada pasa por archivos temporales.
#
# Configuración por variables (en el VPS, /etc/velora/respaldo.env; ver respaldo.env.example):
#   RESTIC_REPOSITORY, RESTIC_PASSWORD_FILE (o RESTIC_PASSWORD), AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
#   VELORA_PG_CONTAINER (velora-postgres), VELORA_DB_NAME (velora), VELORA_DB_USERNAME (velora)
#   VELORA_ORIGINALS_VOLUME (velora-originals)
#   VELORA_KEEP_DAILY (7), VELORA_KEEP_WEEKLY (4), VELORA_KEEP_MONTHLY (6)
#   VELORA_ALERT_REPO y VELORA_ALERT_TOKEN: si están, un fallo abre un issue en GitHub.
set -Eeuo pipefail

PG_CONTAINER="${VELORA_PG_CONTAINER:-velora-postgres}"
DB_NAME="${VELORA_DB_NAME:-velora}"
DB_USER="${VELORA_DB_USERNAME:-velora}"
ORIGINALS_VOLUME="${VELORA_ORIGINALS_VOLUME:-velora-originals}"
HELPER_IMAGE="${VELORA_HELPER_IMAGE:-alpine:3.22}"
HOST_TAG="${VELORA_BACKUP_HOST:-velora}"
step="inicio"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

alert() {
  local code=$1
  log "FALLÓ en el paso «${step}» (código ${code})"
  if [[ -n "${VELORA_ALERT_REPO:-}" && -n "${VELORA_ALERT_TOKEN:-}" ]]; then
    # Sin contenido de la base ni rutas de archivos: solo el paso, el código y dónde ver el registro.
    local body
    # shellcheck disable=SC2016  # las comillas invertidas son Markdown del issue, no expansiones.
    body=$(printf 'El respaldo de Velora falló en el paso **%s** (código %s) el %s en `%s`.\\n\\nRegistro: `journalctl -u velora-respaldo --since today`.\\nRunbook: `ops/respaldos/README.md`.' \
      "$step" "$code" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(hostname)")
    curl -fsS -X POST "https://api.github.com/repos/${VELORA_ALERT_REPO}/issues" \
      -H "Authorization: Bearer ${VELORA_ALERT_TOKEN}" -H "Accept: application/vnd.github+json" \
      -d "{\"title\":\"Respaldo fallido: ${step}\",\"body\":\"${body}\",\"labels\":[\"respaldo\"]}" >/dev/null \
      || log "Tampoco se pudo abrir el issue de alerta."
  fi
  exit "$code"
}
trap 'alert $?' ERR

step="repositorio"
# La primera vez el repositorio no existe: se crea (cifrado con RESTIC_PASSWORD).
if ! restic cat config >/dev/null 2>&1; then
  log "Creando el repositorio de restic"
  restic init
fi

step="base de datos"
log "Respaldando la base ${DB_NAME}"
# Formato custom de pg_dump: comprimido y restaurable con pg_restore. Si pg_dump falla, restic falla.
restic backup --host "$HOST_TAG" --tag velora --tag db --stdin-filename velora-db.dump \
  --stdin-from-command -- docker exec "$PG_CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc

step="originales"
log "Respaldando el volumen ${ORIGINALS_VOLUME}"
restic backup --host "$HOST_TAG" --tag velora --tag originales --stdin-filename velora-originales.tar \
  --stdin-from-command -- docker run --rm -v "${ORIGINALS_VOLUME}:/data:ro" "$HELPER_IMAGE" tar -C /data -cf - .

step="retención"
restic forget --host "$HOST_TAG" --tag velora --group-by host,tags --prune \
  --keep-daily "${VELORA_KEEP_DAILY:-7}" --keep-weekly "${VELORA_KEEP_WEEKLY:-4}" --keep-monthly "${VELORA_KEEP_MONTHLY:-6}"

# Los domingos (o si se pide), una verificación que lee una muestra de los datos: detecta corrupción en el destino.
if [[ "$(date +%u)" == 7 || -n "${VELORA_CHECK_ALWAYS:-}" ]]; then
  step="verificación"
  restic check --read-data-subset=5%
fi

step="fin"
log "Respaldo completo"
