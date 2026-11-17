# Respaldos y restauración (ADR-027)

**Qué se respalda:**
- La base de PostgreSQL, con `pg_dump` en formato custom.
- El volumen de archivos originales (`velora-originals`, ADR-013).

El mapa base no se respalda porque se regenera con `pnpm basemap` en `velora-web`.

**Cómo:** con [restic](https://restic.net), cifrado antes de salir del VPS, hacia un bucket privado de Cloudflare R2. Corre todos los días a las 3:30, hora del centro de México. Se conservan 7 respaldos diarios, 4 semanales y 6 mensuales. Los domingos, además, se lee una muestra del 5 % para detectar corrupción. Si algo falla, se abre un issue con la etiqueta `respaldo` en este repositorio.

**Tope de tamaño:** con `VELORA_MAX_REPO_GB` (2.5 GB en el ejemplo), después de la retención se mide el repositorio con `restic stats --mode raw-data`. Al pasar el 80 % (`VELORA_WARN_REPO_PCT`) y al pasar el tope se abre un issue con la etiqueta `respaldo`, sin repetirlo mientras siga abierto. **Solo avisa:**
- no detiene el respaldo, porque se perdería el RPO;
- no recorta la retención, porque podría borrar el respaldo bueno de antes de un error.

Al recibir el aviso:
- revisa por qué creció (`restic stats --mode raw-data`, `restic snapshots`);
- después, baja `VELORA_KEEP_*` o sube el tope en `/etc/velora/respaldo.env`;
- cierra el issue.

- **RPO:** 24 horas. Se pierde a lo más lo importado desde el último respaldo, y los archivos originales siguen en tu teléfono o tu computadora.
- **RTO:** menos de 1 hora con el volumen actual. Lo que más tarda es levantar el VPS; restaurar unos cientos de MB toma minutos.

## Instalación en el VPS (una vez)

1. **Cloudflare R2:**
   1. Crea el bucket privado `velora-respaldos`.
   2. Crea un token de API de R2 con permiso **Object Read & Write solo sobre ese bucket**.
   3. Anota el Access Key ID, el Secret Access Key y el ID de tu cuenta.
2. **Token de alertas:** un token fine-grained de GitHub con **Issues: Read and write** solo en `1franky/velora-api`. En el repositorio, crea la etiqueta `respaldo`.
3. **restic** (0.17 o posterior, por `--stdin-from-command`):

   ```bash
   sudo apt install restic   # si la versión es menor a 0.17:
   sudo restic self-update
   ```

   El binario debe quedar en una ruta del sistema (`/usr/bin` o `/usr/local/bin`). systemd no busca en `~/.local/bin`, y si restic solo está ahí, el servicio falla con `restic: command not found`. Si lo instalaste en tu usuario, cópialo:

   ```bash
   sudo install -m 755 ~/.local/bin/restic /usr/local/bin/restic
   ```

4. **Secretos:**

   ```bash
   sudo mkdir -p /etc/velora
   sudo cp ops/respaldos/respaldo.env.example /etc/velora/respaldo.env   # y llénalo
   openssl rand -base64 32 | sudo tee /etc/velora/restic-password >/dev/null
   sudo chown root:francisco /etc/velora/respaldo.env /etc/velora/restic-password
   sudo chmod 640 /etc/velora/respaldo.env /etc/velora/restic-password
   ```

   **Guarda la contraseña de `/etc/velora/restic-password` en tu gestor de contraseñas.** Sin ella, los respaldos no se pueden descifrar.

5. **Servicio y temporizador:**

   ```bash
   sudo cp ops/respaldos/velora-respaldo.{service,timer} /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now velora-respaldo.timer
   sudo systemctl start velora-respaldo.service      # primer respaldo, ahora
   journalctl -u velora-respaldo -n 50               # debe terminar en "Respaldo completo"
   ```

## Consultar

```bash
set -a; source /etc/velora/respaldo.env; set +a
restic snapshots                  # instantáneas, con sus etiquetas db y originales
systemctl list-timers velora-respaldo.timer
```

## Restaurar

En un VPS nuevo o tras perder la base, desde `/home/francisco/docker/velora/velora-api`:

1. Instala restic y copia `/etc/velora/respaldo.env` y `restic-password` desde tu gestor de contraseñas (paso 4).
2. Levanta solo PostgreSQL, con la base vacía, y deja la API apagada:

   ```bash
   docker network create velora-net 2>/dev/null || true
   docker compose -f compose.deploy.yaml up -d postgres
   ```

3. Restaura la última instantánea (o una en particular, con su ID de `restic snapshots`):

   ```bash
   set -a; source /etc/velora/respaldo.env; set +a
   ops/respaldos/restaurar.sh                     # o: ops/respaldos/restaurar.sh <id-db> <id-originales>
   ```

   El script se niega a restaurar encima de una base que ya tiene el esquema de Velora.

4. Levanta la API, que aplica las migraciones pendientes con Flyway, y comprueba:

   ```bash
   docker compose -f compose.deploy.yaml up -d
   curl -s http://127.0.0.1:3008/healthz            # {"status":"UP"}
   ```

   Entra a la web y revisa que el historial, el detalle de una rodada y el mapa se vean.

## Prueba

`ops/respaldos/probar.sh` respalda una base PostGIS con datos y un volumen de originales, restaura en otros nuevos y compara filas, geometría y archivos byte a byte. También comprueba que no se restaure encima de datos y que un respaldo sin base de datos falle. El CI la corre en cada PR y en cada push a `main`; en local requiere Docker y restic.

**Prueba de restauración real:** cada tres meses, en una máquina aparte. Es lo único que demuestra que el respaldo sirve. `probar-restauracion.sh` hace todo de una vez:

1. Lee el **100 %** del repositorio (`restic check --read-data`).
2. Restaura la última instantánea, o las que le pases (`<id-db> <id-originales>`), en una base y un volumen desechables, con `restaurar.sh`.
3. Comprueba:
   - que Flyway no tenga migraciones fallidas y que PostGIS responda;
   - que cada actividad tenga su archivo y su track, y que las geometrías sean válidas;
   - que cada original restaurado tenga el SHA-256 registrado en `activity_files`, sin faltantes ni sobrantes.
4. Saca la huella de los datos (`huella.sql`: conteos y md5, sin datos personales) y la compara con la de producción.
5. Borra la base y el volumen al terminar, también si algo falla.

El script solo lee el repositorio: nunca respalda ni hace `forget`. Como `restic check` toma un lock exclusivo, no lo corras cerca de las 3:30.

```bash
# En el VPS: huella de producción (solo lectura)
docker exec -i velora-postgres psql -U velora -d velora -tA < ops/respaldos/huella.sql > huella-produccion.txt

# En la otra máquina, con Docker y restic, desde velora-api/. El respaldo.env es una copia del del VPS, con
# RESTIC_PASSWORD_FILE apuntando a una copia local de la contraseña. Bórralos al terminar.
set -a; source /ruta/a/respaldo.env; set +a
VELORA_HUELLA_ESPERADA=huella-produccion.txt ops/respaldos/probar-restauracion.sh
```

Termina en `✓ Respaldo restaurable y consistente` o con la lista de problemas y código 1. La huella solo difiere si hubo importaciones o borrados después de la instantánea. Opciones:
- `VELORA_CHECK_SUBSET=10` lee solo el 10 % del repositorio;
- `VELORA_CONSERVAR=1` deja la base y el volumen para levantar la API contra la copia.

Registro: 17/11/2026, instantáneas `49e06513` y `3784d3c5`, restauradas en 13 s; huella igual a la de producción.
