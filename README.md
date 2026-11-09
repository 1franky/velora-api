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

## Licencia

[Apache-2.0](LICENSE). El Garmin FIT SDK se usa como dependencia de Maven Central bajo su propia licencia (FIT Protocol License) y no se redistribuye en este repositorio.

## Repositorios relacionados

- `velora-web` — aplicación web (Next.js + TypeScript).
- `velora-android` — aplicación Android (Kotlin + Jetpack Compose).
