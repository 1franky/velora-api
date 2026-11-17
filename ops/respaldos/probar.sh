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

# Un GitHub falso para las alertas: guarda los issues que recibe y los devuelve al listarlos.
cat >"$work/github.py" <<'PY'
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
issues = []
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def reply(self, code, data):
        body = json.dumps(data, indent=2, ensure_ascii=False).encode()  # como GitHub: UTF-8 sin escapar
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.end_headers(); self.wfile.write(body)
    def do_GET(self): self.reply(200, issues)
    def do_POST(self):
        issue = json.loads(self.rfile.read(int(self.headers["Content-Length"])))  # falla si el JSON no es válido
        issues.append(issue)
        with open(sys.argv[2], "a") as f: f.write(issue["title"] + "\n")
        self.reply(201, issue)
s = HTTPServer(("127.0.0.1", 0), H)
open(sys.argv[1], "w").write(str(s.server_port))
s.serve_forever()
PY
python3 "$work/github.py" "$work/puerto" "$work/issues.txt" &
github_pid=$!
trap 'kill $github_pid 2>/dev/null || true; cleanup' EXIT
for _ in $(seq 1 50); do [[ -s "$work/puerto" ]] && break; sleep 0.1; done
VELORA_ALERT_API="http://127.0.0.1:$(cat "$work/puerto")"
export VELORA_ALERT_API VELORA_ALERT_REPO=velora/prueba VELORA_ALERT_TOKEN=prueba

echo "→ Respaldo, con un tope de tamaño diminuto: avisa pero termina bien"
VELORA_PG_CONTAINER="$id-origen" VELORA_ORIGINALS_VOLUME="$id-originales" VELORA_MAX_REPO_GB=0.0001 \
  ops/respaldos/respaldar.sh | tee "$work/respaldo.log"
grep -q "pasó el tope de tamaño" "$work/respaldo.log" || { echo "No avisó del tope de tamaño" >&2; exit 1; }
[[ "$(wc -l <"$work/issues.txt" | tr -d ' ')" == 1 ]] || { echo "Se esperaba un issue de tamaño" >&2; exit 1; }

echo "→ Un segundo respaldo sobre el tope no repite el issue abierto"
VELORA_PG_CONTAINER="$id-origen" VELORA_ORIGINALS_VOLUME="$id-originales" VELORA_MAX_REPO_GB=0.0001 \
  ops/respaldos/respaldar.sh >/dev/null
[[ "$(wc -l <"$work/issues.txt" | tr -d ' ')" == 1 ]] || { echo "El issue de tamaño se repitió" >&2; exit 1; }

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
