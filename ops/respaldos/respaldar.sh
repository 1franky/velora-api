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
#   VELORA_MAX_REPO_GB (sin tope si no está) y VELORA_WARN_REPO_PCT (80): tope de tamaño del repositorio en GB
#     (10^9 bytes). Al pasar el aviso o el tope se abre un issue, pero el respaldo NO se detiene ni borra nada.
set -Eeuo pipefail

PG_CONTAINER="${VELORA_PG_CONTAINER:-velora-postgres}"
DB_NAME="${VELORA_DB_NAME:-velora}"
DB_USER="${VELORA_DB_USERNAME:-velora}"
ORIGINALS_VOLUME="${VELORA_ORIGINALS_VOLUME:-velora-originals}"
HELPER_IMAGE="${VELORA_HELPER_IMAGE:-alpine:3.22}"
HOST_TAG="${VELORA_BACKUP_HOST:-velora}"
step="inicio"

GITHUB_API="${VELORA_ALERT_API:-https://api.github.com}"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

# Abre un issue con la etiqueta `respaldo` si hay token; si ya hay uno abierto con el mismo título, no lo repite (el
# aviso de tamaño se repetiría cada día). Nunca hace fallar al respaldo. El cuerpo va ya escapado para JSON.
open_issue() {
  local title=$1 body=$2
  [[ -n "${VELORA_ALERT_REPO:-}" && -n "${VELORA_ALERT_TOKEN:-}" ]] || return 0
  local auth=(-H "Authorization: Bearer ${VELORA_ALERT_TOKEN}" -H "Accept: application/vnd.github+json")
  if curl -fsS "${auth[@]}" "${GITHUB_API}/repos/${VELORA_ALERT_REPO}/issues?labels=respaldo&state=open&per_page=100" \
    2>/dev/null | grep -qF "\"${title}\""; then
    log "Ya hay un issue abierto: «${title}»"
    return 0
  fi
  curl -fsS -X POST "${auth[@]}" "${GITHUB_API}/repos/${VELORA_ALERT_REPO}/issues" \
    -d "{\"title\":\"${title}\",\"body\":\"${body}\",\"labels\":[\"respaldo\"]}" >/dev/null \
    || log "No se pudo abrir el issue de alerta."
}

alert() {
  local code=$1
  log "FALLÓ en el paso «${step}» (código ${code})"
  # Sin contenido de la base ni rutas de archivos: solo el paso, el código y dónde ver el registro.
  local body
  # shellcheck disable=SC2016  # las comillas invertidas son Markdown del issue, no expansiones.
  body=$(printf 'El respaldo de Velora falló en el paso **%s** (código %s) el %s en `%s`.\\n\\nRegistro: `journalctl -u velora-respaldo --since today`.\\nRunbook: `ops/respaldos/README.md`.' \
    "$step" "$code" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(hostname)")
  open_issue "Respaldo fallido: ${step}" "$body"
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

# Tope de tamaño (ADR-027): se mide después de la retención, que es lo que de verdad ocupa el bucket. Solo avisa: no
# detiene el respaldo (se perdería el RPO) ni recorta la retención (podría borrar el respaldo bueno de antes de un bug).
if [[ -n "${VELORA_MAX_REPO_GB:-}" ]]; then
  step="tamaño"
  # raw-data: los blobs comprimidos y deduplicados, sin contar referencias repetidas. R2 suma algo de índice encima.
  bytes=$(restic stats --mode raw-data --json | sed -n 's/.*"total_size":\([0-9]*\).*/\1/p')
  [[ -n "$bytes" ]]
  read -r size_gb pct level < <(awk -v b="$bytes" -v max="$VELORA_MAX_REPO_GB" -v warn="${VELORA_WARN_REPO_PCT:-80}" \
    'BEGIN { p = b / (max * 1e9) * 100; printf "%.3f %.0f %s\n", b / 1e9, p, (p >= 100 ? "tope" : (p >= warn ? "aviso" : "ok")) }')
  log "Repositorio: ${size_gb} GB de ${VELORA_MAX_REPO_GB} GB (${pct} %)"
  if [[ "$level" != ok ]]; then
    if [[ "$level" == tope ]]; then title="Respaldo: el repositorio pasó el tope de tamaño"; else
      title="Respaldo: el repositorio pasó el ${VELORA_WARN_REPO_PCT:-80} % del tope de tamaño"; fi
    log "AVISO: ${title}. El respaldo sigue; revisa la retención o el tope."
    # shellcheck disable=SC2016  # las comillas invertidas son Markdown del issue, no expansiones.
    body=$(printf 'El repositorio de respaldos ocupa **%s GB** de un tope de %s GB (**%s %%**), el %s en `%s`.\\n\\nEl respaldo sigue corriendo y no se borró nada. Opciones: bajar la retención (`VELORA_KEEP_*`) o subir el tope (`VELORA_MAX_REPO_GB`) en `/etc/velora/respaldo.env`, o revisar por qué creció (`restic stats --mode raw-data`, `restic snapshots`).\\nRunbook: `ops/respaldos/README.md`.' \
      "$size_gb" "$VELORA_MAX_REPO_GB" "$pct" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(hostname)")
    open_issue "$title" "$body"
  fi
fi

# Los domingos (o si se pide), una verificación que lee una muestra de los datos: detecta corrupción en el destino.
if [[ "$(date +%u)" == 7 || -n "${VELORA_CHECK_ALWAYS:-}" ]]; then
  step="verificación"
  restic check --read-data-subset=5%
fi

step="fin"
log "Respaldo completo"
