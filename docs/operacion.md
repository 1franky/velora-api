# Operación de Velora en producción

Runbook para desplegar, revisar, revertir y recuperar Velora (10-security-and-operations.md §6). Los respaldos tienen su propio runbook: [`ops/respaldos/README.md`](../ops/respaldos/README.md).

## Cómo está desplegado

```text
Internet ──HTTPS──▶ Túnel de Cloudflare ──▶ VPS (arm64)
                                             ├─ velora-web  (Next.js)   127.0.0.1:3007
                                             ├─ velora-api  (Spring)    127.0.0.1:3008
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
curl -s https://velora-api.franciscolopez.uk/actuator/health        # {"groups":[...],"status":"UP"}
curl -sI https://velora.franciscolopez.uk/login | grep -i content-security-policy
```

El workflow **Salud de producción** hace estas mismas revisiones cada 15 minutos (ver Monitoreo).

## Revertir un despliegue

Desplegar siempre toma `main`, así que revertir es un PR:

1. En GitHub, en el PR que causó el problema, usa **Revert** y luego integra ese PR de reversión. El CI y el despliegue corren solos.
2. **Si el PR incluía una migración de Flyway**, revertir el código no deshace el esquema; Flyway solo avanza. Hay dos caminos:
   - **Si la migración solo agregó cosas** (tablas, columnas, índices): el código anterior suele funcionar igual. Revisa `/actuator/health` y la web.
   - **Si cambió o borró datos:** restaura el respaldo anterior a la migración (`ops/respaldos/README.md` → Restaurar, con el ID de la instantánea) y despliega el código revertido.

Urgente y sin GitHub: en el VPS, `git checkout <commit-bueno>` y `docker compose -f compose.deploy.yaml up --build -d`. El siguiente despliegue automático vuelve a `main`, así que haz también el PR de reversión.

## Revisar qué pasa

```bash
cd /home/francisco/docker/velora/velora-api
docker compose -f compose.deploy.yaml ps
docker logs --since 1h velora-api          # sin tokens ni coordenadas; los 500 traen errorId
docker logs --since 1h velora-web
docker exec velora-postgres psql -U velora -d velora -c "select status, count(*) from import_jobs group by 1"
df -h / && docker system df                 # espacio en disco
```

Un usuario que reporta un error 500 ve un `errorId`. Búscalo con `docker logs velora-api | grep <errorId>`. Las excepciones se registran sin sus mensajes (`RedactedException`): el tipo y la traza indican dónde falló, y el `errorId` o el id del trabajo de importación sirven para reproducirlo.

## Reiniciar

```bash
docker compose -f compose.deploy.yaml restart api        # en velora-api; o "web" en velora-web
```

Las importaciones que estaban en proceso se recuperan solas al arrancar: los trabajos que quedaron a medias se reintentan.

## Monitoreo

- **Salud de producción** (`.github/workflows/salud.yml`): cada 15 minutos revisa que la API responda `UP`, que la web cargue con su CSP y que el mapa responda 401 sin sesión. Con tres fallas seguidas abre un issue con la etiqueta `caida`, o comenta en el abierto, y lo cierra cuando se recupera. GitHub avisa por correo.
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
