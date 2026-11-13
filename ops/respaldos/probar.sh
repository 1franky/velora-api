#!/usr/bin/env bash
# Prueba de punta a punta del respaldo y la restauración (la corre el CI; también en local con Docker y restic):
# una base PostGIS con datos y un volumen de originales → respaldar.sh a un repositorio restic local → restaurar.sh en
# una base y un volumen nuevos → mismas filas, misma geometría y mismos archivos byte a byte.
set -Eeuo pipefail
cd "$(dirname "$0")/../.."

id="prueba-respaldo-$$"
work=$(mktemp -d)
export RESTIC_REPOSITORY="$work/repositorio"
export RESTIC_PASSWORD="contraseña-de-prueba-$$"
export VELORA_DB_NAME=velora VELORA_DB_USERNAME=velora VELORA_BACKUP_HOST=prueba VELORA_CHECK_ALWAYS=1
image=velora-postgis:prueba

cleanup() {
  docker rm -f "$id-origen" "$id-destino" >/dev/null 2>&1 || true
  docker volume rm -f "$id-originales" "$id-restaurados" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

echo "→ Imagen de PostgreSQL + PostGIS del despliegue"
docker build -q -t "$image" docker/postgis >/dev/null

start_db() {
  docker run -d --name "$1" -e POSTGRES_DB=velora -e POSTGRES_USER=velora -e POSTGRES_PASSWORD=velora "$image" >/dev/null
  for _ in $(seq 1 60); do
    # pg_isready responde antes de que termine el arranque inicial: se confirma con una consulta real.
    docker exec "$1" psql -U velora -d velora -tAc "select 1" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "PostgreSQL no arrancó en $1" >&2
  return 1
}

start_db "$id-origen"
docker exec -i "$id-origen" psql -q -v ON_ERROR_STOP=1 -U velora -d velora <<'SQL'
create extension postgis;
create table activities (id int primary key, title text not null, track geometry(LineString, 4326));
insert into activities
  select g, 'Rodada ' || g, st_makeline(st_makepoint(-99.1 - g * 0.001, 19.4), st_makepoint(-99.2, 19.5))
  from generate_series(1, 500) g;
SQL

docker volume create "$id-originales" >/dev/null
docker run --rm -v "$id-originales:/data" alpine:3.22 sh -c \
  'mkdir -p /data/2026/11 && head -c 300000 /dev/urandom > /data/2026/11/a.fit && echo "<gpx/>" > /data/2026/11/b.gpx'

echo "→ Respaldo"
VELORA_PG_CONTAINER="$id-origen" VELORA_ORIGINALS_VOLUME="$id-originales" ops/respaldos/respaldar.sh

echo "→ Restauración en una base y un volumen nuevos"
start_db "$id-destino"
docker volume create "$id-restaurados" >/dev/null
VELORA_RESTORE_PG_CONTAINER="$id-destino" VELORA_RESTORE_ORIGINALS_VOLUME="$id-restaurados" ops/respaldos/restaurar.sh

echo "→ Comparación"
fingerprint="select count(*), md5(string_agg(id || title || st_astext(track), ',' order by id)) from activities"
before=$(docker exec "$id-origen" psql -U velora -d velora -tAc "$fingerprint")
after=$(docker exec "$id-destino" psql -U velora -d velora -tAc "$fingerprint")
[[ "$before" == "$after" ]] || { echo "La base restaurada no coincide: $before ≠ $after" >&2; exit 1; }

hashes() { docker run --rm -v "$1:/data:ro" alpine:3.22 sh -c 'cd /data && find . -type f | sort | xargs sha256sum'; }
[[ "$(hashes "$id-originales")" == "$(hashes "$id-restaurados")" ]] || { echo "Los originales restaurados no coinciden" >&2; exit 1; }

echo "→ Una base que ya tiene datos no se sobrescribe"
if VELORA_RESTORE_PG_CONTAINER="$id-origen" VELORA_RESTORE_ORIGINALS_VOLUME="$id-restaurados" ops/respaldos/restaurar.sh 2>/dev/null; then
  echo "restaurar.sh aceptó una base con datos" >&2
  exit 1
fi

echo "→ Si la base no responde, el respaldo falla (no termina vacío)"
if VELORA_PG_CONTAINER="$id-no-existe" VELORA_ORIGINALS_VOLUME="$id-originales" ops/respaldos/respaldar.sh >/dev/null 2>&1; then
  echo "respaldar.sh terminó bien sin base de datos" >&2
  exit 1
fi

echo "✓ Respaldo y restauración verificados ($before)"
