# velora-api

Backend de **Velora**, plataforma personal de analítica de ciclismo (ruta y MTB).

> Nombre provisional. Estado: Fase 0 — scaffold base, sin endpoints de negocio.

## Stack

| Componente | Versión | Notas |
|---|---|---|
| Java | 25 LTS | Toolchain de Gradle (ADR-007) |
| Spring Boot | 4.1.2 | Web MVC, Validation, Actuator, Data JPA |
| Gradle | 9.9.0 | Wrapper incluido, Kotlin DSL (ADR-008) |
| PostgreSQL + PostGIS | 18 + 3.7 | Imagen `postgis/postgis:18-3.7` |
| Flyway | gestionado por Boot | Migraciones en `src/main/resources/db/migration` |
| Spring Modulith | 2.1.1 | Verifica límites entre módulos en pruebas |
| Testcontainers | gestionado por Boot | Pruebas de integración contra PostGIS real |
| Spotless + palantir-java-format | 8.11.0 / 2.103.0 | Formato verificado en `./gradlew build` |
| Garmin FIT SDK | 21.221.0 | FIT Protocol License (propietaria); ADR-009 |

## Módulos

Cada paquete directo de `mx.velora.api` es un módulo de aplicación (Spring Modulith):
`identity`, `catalog`, `importing`, `activity`, `geospatial`, `analytics`, `routes`, `goals`, `integration`, `platform`.

El módulo de importación se llama `importing` porque `import` es palabra reservada en Java.

## Requisitos

- JDK 25 (Gradle lo usa vía toolchain).
- Docker con Docker Compose.

## Desarrollo local

```bash
cp .env.example .env              # define VELORA_DB_PASSWORD; ajusta puertos si están ocupados
docker compose up -d --wait       # PostgreSQL + PostGIS en 127.0.0.1:${VELORA_DB_PORT}
./gradlew bootRun                 # perfil `local`; carga variables de `.env`
curl localhost:8080/actuator/health
```

Si cambias `VELORA_DB_PORT`, actualiza también el puerto en `VELORA_DB_URL`.
El puerto HTTP se configura con `VELORA_API_PORT` (8080 por defecto).

## Autenticación

Un solo usuario propietario, sin registro público (ADR-010). Para crearlo una vez:

```bash
VELORA_OWNER_EMAIL=tu@correo.mx VELORA_OWNER_PASSWORD='mínimo 12 caracteres' ./gradlew bootstrapOwner
```

El comando falla si ya existe un usuario. Después, `POST /api/v1/auth/login` devuelve un access token (15 min) y un refresh token (30 días, de un solo uso: reutilizarlo revoca la sesión). Las rutas de `/api/v1` requieren `Authorization: Bearer <accessToken>`; los intentos fallidos de login se limitan por correo e IP. Contraseñas con Argon2id; en base de datos solo se guardan hashes de los tokens.

## Comandos

| Comando | Qué hace |
|---|---|
| `./gradlew build` | Compila, verifica formato y ejecuta todas las pruebas (requiere Docker) |
| `./gradlew test` | Solo pruebas |
| `./gradlew spotlessApply` | Aplica el formato |
| `./gradlew bootstrapOwner` | Crea el usuario propietario (una sola vez) |

## Convenciones ya activas

- Errores en `application/problem+json` (RFC 9457) con textos en `es-MX` (`messages.properties`).
- Los 500 incluyen `errorId` para correlacionar con logs; nunca exponen mensajes internos ni stack traces.
- Health, liveness y readiness en `/actuator/health`, `/actuator/health/liveness` y `/actuator/health/readiness`.
- Hibernate en `ddl-auto: validate`: el esquema solo cambia mediante Flyway.
- Instantes persistidos en UTC.

## Contrato HTTP

`openapi/openapi-v1.yaml` (OpenAPI 3.1) es la fuente de verdad de la API para `velora-web` y `velora-android` (ADR-011). Cualquier cambio de endpoints o campos se hace en ese archivo dentro del mismo PR que lo implementa. `OpenApiContract` (en pruebas) valida solicitudes y respuestas contra él.

## Importación de actividades

El módulo `importing` detecta el formato por contenido (no por extensión) y lo convierte en un `ImportedActivity` canónico. Estado actual:

| Formato | Estado |
|---|---|
| GPX 1.0/1.1 | Soportado (parser StAX propio, con extensiones de sensores habituales) |
| TCX | Soportado (TrainingCenterDatabase v2: vueltas, sensores y extensiones ActivityExtension v2) |
| FIT | Soportado (Garmin FIT SDK 21.221.0 tras un adaptador; actividades, vueltas, pausas del cronómetro) |

El lector XML rechaza DTD y entidades externas (XXE). Los errores nunca incluyen contenido del archivo.

Después del parseo, `ActivityValidator` rechaza actividades sin muestras, sin tiempos o con tiempos imposibles, y sanea coordenadas inválidas, saltos de GPS y valores negativos dejando el dato en `null` con una advertencia. `ActivityFootprint` resume inicio, duración, distancia (informada o derivada) y recuadro para la deduplicación heurística (`DuplicateHeuristic`), que solo advierte y nunca descarta. Reglas y umbrales en ADR-009. Los fixtures de `src/test/resources/fixtures/` son sintéticos.

## Importar actividades por la API

```bash
curl -H "Authorization: Bearer $TOKEN" -F file=@rodada.fit [-F bikeId=...] [-F activityTitle=...] \
     http://localhost:8080/api/v1/activities/imports        # 202 { jobId, status: QUEUED, statusUrl }
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/imports/<jobId>
```

Un worker dentro de la API procesa la cola (`import_jobs`, `FOR UPDATE SKIP LOCKED`): verifica el SHA-256 del original, parsea, valida, deriva métricas, deduplica y registra. Estados: `COMPLETED`, `DUPLICATE` (mismo archivo o mismo `file_id` FIT; el original repetido se descarta), `REJECTED` (archivo inválido, con `errorCode` y `errorDetail` en es-MX), `FAILED` (tras 3 intentos con espera creciente). Un trabajo interrumpido por un reinicio se recupera al vencer su bloqueo (5 min). Configuración en `velora.import.*`.

## Actividades

`POST /api/v1/activities/{id}/reprocess` recalcula una actividad desde su original en la misma cola (trabajo `REPROCESS`): no crea actividades ni toca el original, conserva título, bicicleta, dispositivo y zona horaria, y guarda las métricas anteriores en `activity_metrics_history` para compararlas.

Criterio de salida de la Fase 2 (`HundredActivitiesExitCriterionTest`, parte del build): 100 actividades FIT de 30 min a 1 Hz se suben, procesan y consultan por la API en ~12 s.

Consulta: `GET /api/v1/activities` (de la más reciente a la más antigua; filtros `from`/`to` por fecha deportiva local, `bikeId`, `subType`, `minDistanceM`/`maxDistanceM`; paginación con `cursor` opaco y `limit` ≤ 100), `GET /activities/{id}` (con procedencia de cada métrica), `/streams?types=…` y `/laps`. `DELETE /activities/{id}` borra todo lo derivado y, tras confirmar la transacción, el archivo original; el mismo archivo puede volver a importarse.

El módulo `activity` registra actividades validadas en una sola transacción (ADR-012): `activities` con valores efectivos (prefieren el dato del dispositivo), `activity_metrics` con métricas del dispositivo y derivadas por separado, `activity_tracks` (`MultiLineString`, un tramo por segmento), vueltas, streams en `bytea` con el códec `velora-streams/1` y la referencia al archivo original.

Los originales se guardan en disco (ADR-013) en `VELORA_STORAGE_ORIGINALS_PATH` (por defecto `data/originals`, ignorado por Git), con escritura atómica, permisos solo para el propietario del proceso y verificación de SHA-256 al leer.

## Despliegue (VPS)

Automático con GitHub Actions, mismo esquema que nexora: cuando `CI` termina con éxito en `main`, `.github/workflows/deploy.yml` entra por SSH al VPS y ejecuta en `/home/francisco/docker/velora/velora-api`:

```bash
git pull --ff-only origin main
docker compose -f compose.deploy.yaml up --build -d
```

- `compose.deploy.yaml`: API (`Dockerfile`) + PostgreSQL 18/PostGIS 3.7. El VPS es arm64 y `postgis/postgis` solo existe para amd64, por eso se construye `docker/postgis/Dockerfile` desde `postgres:18` + PGDG. `compose.yaml` sigue siendo solo para desarrollo.
- La API se publica solo en `127.0.0.1:${VELORA_API_HOST_PORT:-3008}`; el acceso público (velora-android) entra por el túnel de Cloudflare con HTTPS. `velora-web` la alcanza por la red externa `velora-net` (`docker network create velora-net`). PostgreSQL no publica puertos.
- Detrás del túnel, `getRemoteAddr()` es la IP del proxy, no la del cliente: el límite de intentos de login por IP se comparte entre todos los clientes (el límite por correo no cambia).
- Volúmenes (incluir en respaldos): `velora-postgres-data` y `velora-originals` (ADR-013).
- `.env` del VPS (no versionado): `VELORA_DB_NAME`, `VELORA_DB_USERNAME`, `VELORA_DB_PASSWORD`, `VELORA_API_HOST_PORT`.
- Secretos del repositorio: `VPS_HOST` y `VPS_DEPLOY_KEY`. "Run workflow" redespliega sin commit.

Propietario en producción (una sola vez):

```bash
docker compose -f compose.deploy.yaml run --rm --no-deps \
  -e VELORA_OWNER_EMAIL=tu@correo.mx -e VELORA_OWNER_PASSWORD='mínimo 12 caracteres' \
  api --velora.bootstrap-owner --spring.main.web-application-type=none
```

## Licencia

[Apache-2.0](LICENSE). El Garmin FIT SDK se usa como dependencia de Maven Central bajo su propia licencia (FIT Protocol License) y no se redistribuye en este repositorio.

## Repositorios relacionados

- `velora-web` — aplicación web (Next.js + TypeScript).
- `velora-android` — aplicación Android (Kotlin + Jetpack Compose).
