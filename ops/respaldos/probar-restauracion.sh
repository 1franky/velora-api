#!/usr/bin/env bash
# Prueba de restauración REAL (ADR-027): restaura una instantánea del repositorio de restic (el bucket de R2) en una base
# y un volumen desechables, en una máquina aparte, y comprueba que los datos sirven. Cada tres meses (ops/respaldos/README.md).
#
#   set -a; source respaldo.env; set +a
#   ops/respaldos/probar-restauracion.sh [instantánea-db] [instantánea-originales]   (por omisión, las más recientes)
#
# Solo lee el repositorio: nunca respalda ni hace forget/prune. `restic check` toma un lock exclusivo, así que no lo
# corras cerca de las 3:30 (hora del respaldo en el VPS).
#
# Variables opcionales:
#   VELORA_HUELLA_ESPERADA   archivo con la salida de huella.sql en producción: se compara con la copia restaurada.
#   VELORA_CHECK_SUBSET      porcentaje para `restic check --read-data-subset` (por omisión se lee el 100 %).
#   VELORA_CONSERVAR=1       no borra la base ni el volumen al terminar (para levantar la API contra la copia).
set -Eeuo pipefail
cd "$(dirname "$0")/../.."

: "${RESTIC_REPOSITORY:?Carga primero el respaldo.env (set -a; source respaldo.env; set +a)}"

DB_SNAPSHOT="${1:-latest}"
FILES_SNAPSHOT="${2:-latest}"
id="velora-prueba-restauracion"
pg="$id-pg"
volume="$id-originales"
image="velora-postgis:prueba-restauracion"
helper="${VELORA_HELPER_IMAGE:-alpine:3.22}"
work=$(mktemp -d)
fallas=0

paso() { printf '\n→ %s\n' "$*"; }
bien() { printf '  ✓ %s\n' "$*"; }
mal() { printf '  ✗ %s\n' "$*" >&2; fallas=$((fallas + 1)); }
# Un conteo de problemas: 0 es bien, cualquier otro número es una falla.
cero() { if [[ "$1" == 0 ]]; then bien "$2"; else mal "$3"; fi; }
sql() { docker exec -i "$pg" psql -U velora -d velora -v ON_ERROR_STOP=1 -tA "$@"; }

cleanup() {
  rm -rf "$work"
  if [[ -n "${VELORA_CONSERVAR:-}" ]]; then
    printf '\nSe conservan %s y el volumen %s. Al terminar:\n  docker rm -f %s; docker volume rm %s\n' "$pg" "$volume" "$pg" "$volume"
  else
    docker rm -f "$pg" >/dev/null 2>&1 || true
    docker volume rm -f "$volume" >/dev/null 2>&1 || true
  fi
}

# Sin restos de una corrida anterior: restaurar.sh se niega a escribir sobre datos, y la prueba debe partir de cero.
if docker inspect "$pg" >/dev/null 2>&1 || docker volume inspect "$volume" >/dev/null 2>&1; then
  echo "Ya existe $pg o el volumen $volume (¿una prueba anterior con VELORA_CONSERVAR?). Bórralos primero:" >&2
  echo "  docker rm -f $pg; docker volume rm $volume" >&2
  exit 1
fi
trap cleanup EXIT

paso "Instantáneas en el repositorio"
restic snapshots --tag velora --latest 1 --group-by tags

paso "Integridad del repositorio"
if [[ -n "${VELORA_CHECK_SUBSET:-}" ]]; then
  restic check --read-data-subset="${VELORA_CHECK_SUBSET}%"
else
  restic check --read-data
fi

paso "Base y volumen desechables"
docker build -q -t "$image" docker/postgis >/dev/null
docker run -d --name "$pg" -e POSTGRES_DB=velora -e POSTGRES_USER=velora \
  -e POSTGRES_PASSWORD="$(openssl rand -hex 16)" "$image" >/dev/null
docker volume create "$volume" >/dev/null
for _ in $(seq 1 60); do
  # pg_isready responde antes de que termine el arranque inicial: se confirma con una consulta real.
  sql -c "select 1" >/dev/null 2>&1 && break
  sleep 1
done
sql -c "select 1" >/dev/null

paso "Restauración"
inicio=$(date +%s)
VELORA_RESTORE_PG_CONTAINER="$pg" VELORA_RESTORE_ORIGINALS_VOLUME="$volume" \
  ops/respaldos/restaurar.sh "$DB_SNAPSHOT" "$FILES_SNAPSHOT"
duracion=$(($(date +%s) - inicio))
bien "restaurado en ${duracion} s"

paso "Esquema"
fallidas=$(sql -c "select count(*) from flyway_schema_history where not success")
version=$(sql -c "select max(version::int) from flyway_schema_history where success")
cero "$fallidas" "Flyway en la versión ${version}, sin migraciones fallidas" "${fallidas} migraciones fallidas"
if postgis=$(sql -c "select postgis_lib_version()" 2>/dev/null); then bien "PostGIS ${postgis}"; else mal "PostGIS no responde"; fi

paso "Datos"
read -r actividades sin_archivo sin_track < <(sql -F ' ' -c "
  select count(*),
         count(*) filter (where not exists (select 1 from activity_files f where f.activity_id = a.id)),
         count(*) filter (where not exists (select 1 from activity_tracks t where t.activity_id = a.id))
  from activities a")
if [[ "$actividades" -gt 0 ]]; then bien "${actividades} actividades"; else mal "la base restaurada no tiene actividades"; fi
cero "$sin_archivo" "todas tienen su archivo original" "${sin_archivo} actividades sin archivo original"
cero "$sin_track" "todas tienen su track" "${sin_track} actividades sin track"
invalidas=$(sql -c "select count(*) from activity_tracks where route_geom is not null and not st_isvalid(route_geom)")
cero "$invalidas" "geometrías válidas" "${invalidas} geometrías inválidas"

paso "Originales contra la base (SHA-256)"
# activity_files guarda el SHA-256 de cada original al importarlo: cada archivo restaurado debe coincidir, sin faltantes
# ni sobrantes. Se comparan solo hashes y rutas internas (UUID), nunca contenido.
sql -c "select encode(sha256, 'hex') || '  ./' || storage_key from activity_files" | sort -k2 >"$work/base.txt"
docker run --rm -v "$volume:/data:ro" "$helper" sh -c 'cd /data && find . -type f -exec sha256sum {} +' \
  | sort -k2 >"$work/volumen.txt"
if diff -q "$work/base.txt" "$work/volumen.txt" >/dev/null; then
  bien "$(wc -l <"$work/volumen.txt" | tr -d ' ') archivos idénticos a lo registrado en la base"
else
  faltan=$(comm -23 <(cut -c67- "$work/base.txt") <(cut -c67- "$work/volumen.txt") | wc -l | tr -d ' ')
  sobran=$(comm -13 <(cut -c67- "$work/base.txt") <(cut -c67- "$work/volumen.txt") | wc -l | tr -d ' ')
  distintos=$(join -j 2 "$work/base.txt" "$work/volumen.txt" | awk '$2 != $3' | wc -l | tr -d ' ')
  mal "originales: ${faltan} faltan, ${sobran} sobran, ${distintos} con contenido distinto"
fi

paso "Huella"
sql <ops/respaldos/huella.sql >"$work/huella.txt"
sed 's/|/: /' "$work/huella.txt" | sed 's/^/  /'
if [[ -n "${VELORA_HUELLA_ESPERADA:-}" ]]; then
  if diff <(tr -d '\r' <"$VELORA_HUELLA_ESPERADA") "$work/huella.txt" >"$work/huella.diff"; then
    bien "igual a la huella de producción"
  else
    mal "distinta de la huella de producción (normal solo si hubo importaciones o borrados después de la instantánea):"
    sed 's/^/    /' "$work/huella.diff" >&2
  fi
else
  echo "  (Para compararla con producción: VELORA_HUELLA_ESPERADA=<archivo>; ver huella.sql)"
fi

echo
if [[ "$fallas" -gt 0 ]]; then
  echo "✗ La prueba de restauración encontró ${fallas} problema(s)." >&2
  exit 1
fi
echo "✓ Respaldo restaurable y consistente (restauración en ${duracion} s)."
