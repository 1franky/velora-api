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

## Comandos

| Comando | Qué hace |
|---|---|
| `./gradlew build` | Compila, verifica formato y ejecuta todas las pruebas (requiere Docker) |
| `./gradlew test` | Solo pruebas |
| `./gradlew spotlessApply` | Aplica el formato |

## Convenciones ya activas

- Errores en `application/problem+json` (RFC 9457) con textos en `es-MX` (`messages.properties`).
- Los 500 incluyen `errorId` para correlacionar con logs; nunca exponen mensajes internos ni stack traces.
- Health, liveness y readiness en `/actuator/health`, `/actuator/health/liveness` y `/actuator/health/readiness`.
- Hibernate en `ddl-auto: validate`: el esquema solo cambia mediante Flyway.
- Instantes persistidos en UTC.

## Repositorios relacionados

- `velora-web` — aplicación web (Next.js + TypeScript).
- `velora-android` — aplicación Android (Kotlin + Jetpack Compose).
