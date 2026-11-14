# Operación de Velora en producción

Runbook para desplegar, revisar, revertir y recuperar Velora (10-security-and-operations.md §6). Los respaldos tienen su propio runbook: [`ops/respaldos/README.md`](../ops/respaldos/README.md).

## Cómo está desplegado

```text
Internet ──HTTPS──▶ Túnel de Cloudflare ──▶ VPS (arm64)
                                             ├─ velora-web  (Next.js)   127.0.0.1:3007
                                             ├─ velora-api  (Spring)    127.0.0.1:3008 (Actuator en :8081, solo velora-net)
                                             └─ velora-postgres (PostGIS) sin puertos, solo red velora-net
```

| | Web | API |
|---|---|---|
| URL pública | https://velora.franciscolopez.uk | https://velora-api.franciscolopez.uk |
| Carpeta en el VPS | `/home/francisco/docker/velora/velora-web` | `/home/francisco/docker/velora/velora-api` |
| Compose | `compose.deploy.yaml` | `compose.deploy.yaml` (API + PostgreSQL) |
| Secretos | variables del compose | `.env` junto al compose (`VELORA_DB_PASSWORD`) |
| Volúmenes | `velora-basemap` (mapa base) | `velora-postgres-data`, `velora-originals` |

Los dos proyectos comparten la red Docker externa `velora-net`. La web es el único cliente de la API dentro del VPS (ADR-014). Android entra por la URL pública de la API.

## Desplegar

Es automático. Al integrar un PR a `main`, el workflow **CI** corre, y si pasa, **Deploy a VPS** entra por SSH, hace `git pull --ff-only` y `docker compose -f compose.deploy.yaml up --build -d`. Para redesplegar sin cambios: **Actions → Deploy a VPS → Run workflow**.

Después de cada despliegue:

```bash
curl -s https://velora-api.franciscolopez.uk/healthz               # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' https://velora-api.franciscolopez.uk/actuator/metrics   # 404: Actuator no es público
curl -sI https://velora.franciscolopez.uk/login | grep -i content-security-policy
```

El workflow **Salud de producción** hace estas mismas revisiones cada 15 minutos (ver Monitoreo).

## Revertir un despliegue

Desplegar siempre toma `main`, así que revertir es un PR:

1. En GitHub, en el PR que causó el problema, usa **Revert** y luego integra ese PR de reversión. El CI y el despliegue corren solos.
2. **Si el PR incluía una migración de Flyway**, revertir el código no deshace el esquema; Flyway solo avanza. Hay dos caminos:
   - **Si la migración solo agregó cosas** (tablas, columnas, índices): el código anterior suele funcionar igual. Revisa `/healthz` y la web.
   - **Si cambió o borró datos:** restaura el respaldo anterior a la migración (`ops/respaldos/README.md` → Restaurar, con el ID de la instantánea) y despliega el código revertido.

Urgente y sin GitHub: en el VPS, `git checkout <commit-bueno>` y `docker compose -f compose.deploy.yaml up --build -d`. El siguiente despliegue automático vuelve a `main`, así que haz también el PR de reversión.

## Revisar qué pasa

```bash
cd /home/francisco/docker/velora/velora-api
docker compose -f compose.deploy.yaml ps
docker logs --since 1h velora-web
docker exec velora-postgres psql -U velora -d velora -c "select status, count(*) from import_jobs group by 1"
df -h / && docker system df                 # espacio en disco
```

### Logs de la API

La API escribe un objeto JSON por línea (formato ECS). Las líneas no incluyen tokens ni coordenadas. Las recetas usan `jq` (`sudo apt install jq` si falta). Cada línea de una solicitud lleva su `requestId`, y cada línea de un trabajo de importación lleva su `importJobId`. Se leen con `jq`:

```bash
# Legible: hora, nivel, id y mensaje
docker logs --since 1h velora-api 2>&1 | jq -rR 'fromjson? | [."@timestamp", .log.level, (.requestId // .importJobId // "-"), .message] | @tsv'
# Solo advertencias y errores, con su traza
docker logs --since 24h velora-api 2>&1 | jq -cR 'fromjson? | select(.log.level == "WARN" or .log.level == "ERROR")'
# Todo lo de una solicitud o de un trabajo de importación
docker logs velora-api 2>&1 | jq -cR 'fromjson? | select(.requestId == "<id>")'
docker logs velora-api 2>&1 | jq -cR 'fromjson? | select(.importJobId == "<id-del-trabajo>")'
```

Un usuario que reporta un error 500 ve un `errorId`: es el `requestId` de esa solicitud, y también va en la cabecera `X-Request-Id` de cada respuesta. Con él salen todas las líneas de la solicitud, no solo la del error. Las excepciones se registran sin sus mensajes (`RedactedException`): el tipo y la traza (`error.stack_trace`) indican dónde falló.

### Métricas

Actuator escucha en el puerto 8081 del contenedor, que no se publica ni pasa por el túnel. Se consulta desde la red `velora-net`, porque la imagen no trae `curl`:

```bash
m() { docker run --rm --network velora-net curlimages/curl:8.11.1 -s "http://velora-api:8081/actuator/metrics/$1" | jq -c '[.measurements[] | {(.statistic): .value}] | add'; }
m 'velora.import.queue?tag=status:queued'          # trabajos en espera
m 'velora.import.queue?tag=status:processing'      # en proceso
m 'velora.import.jobs?tag=outcome:failed'          # fallidos desde el último arranque (también: completed, duplicate, rejected, retry, abandoned)
m 'velora.import.duration?tag=outcome:completed'   # COUNT, TOTAL_TIME (s) y MAX de los intentos exitosos
m 'http.server.requests?tag=status:500'            # errores 500 de la API
```

Los contadores se reinician con cada despliegue. Para ver todas las métricas: `.../actuator/metrics` sin nombre.

## Reiniciar

```bash
docker compose -f compose.deploy.yaml restart api        # en velora-api; o "web" en velora-web
```

Las importaciones que estaban en proceso se recuperan solas al arrancar: los trabajos que quedaron a medias se reintentan.

## Monitoreo

- **Salud de producción** (`.github/workflows/salud.yml`): cada 15 minutos revisa que la API responda `UP` en `/healthz`, que la web cargue con su CSP y que el mapa responda 401 sin sesión. Con tres fallas seguidas abre un issue con la etiqueta `caida`, o comenta en el abierto, y lo cierra cuando se recupera. GitHub avisa por correo.
- **Respaldos:** si fallan, abren un issue con la etiqueta `respaldo`.
- **Dependabot:** cada lunes abre PRs de actualización en los tres repositorios. Integrarlos solo si su CI pasa. Los de la API y la web se despliegan solos.
- **Límite de GitHub:** suspende los workflows programados de un repositorio público tras 60 días sin actividad. Si Velora pasa dos meses sin cambios, reactívalo en **Actions → Salud de producción → Enable workflow**.

## Tareas periódicas

| Cada | Tarea |
|---|---|
| Semana | Revisar e integrar los PRs de Dependabot |
| 3 meses | Prueba de restauración real en una máquina aparte (`ops/respaldos/README.md`) |
| 6 meses | Regenerar el mapa base con `pnpm basemap` en `velora-web` y copiarlo al volumen `velora-basemap` |
| Año | Rotar los tokens de R2 y de alertas (`/etc/velora/respaldo.env`) |

## Recuperación ante desastre (VPS perdido)

1. Un VPS nuevo con Docker, el usuario `francisco` en el grupo `docker` y el túnel de Cloudflare apuntando a los puertos 3007 y 3008.
2. Clona los dos repositorios en `/home/francisco/docker/velora/`. Copia el `.env` de la API desde tu gestor de contraseñas y crea la red con `docker network create velora-net`.
3. Restaura la base y los originales: `ops/respaldos/README.md` → Restaurar.
4. Levanta la API y la web, copia el mapa base al volumen `velora-basemap` (o regéneralo) y reinstala el timer de respaldos.
5. Actualiza los secretos `VPS_HOST` y `VPS_DEPLOY_KEY` de los workflows de despliegue de los dos repositorios.

**RPO:** 24 horas, con un respaldo diario. **RTO:** menos de 1 hora, una vez que el VPS existe.
