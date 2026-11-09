# velora-api

Backend de **Velora**, plataforma personal de analítica de ciclismo (ruta y MTB).

> Nombre provisional. Estado: Fase 0 — preparación. Aún no hay código de aplicación.

## Stack previsto

- Kotlin + Spring Boot, arquitectura *modular monolith*.
- PostgreSQL + PostGIS, migraciones con Flyway.
- Contrato HTTP OpenAPI 3.1 versionado.
- Docker Compose para desarrollo.

Las versiones exactas se validarán al crear el scaffold (ver roadmap, Fase 0).

## Módulos previstos

`identity`, `catalog`, `import`, `activity`, `geospatial`, `analytics`, `routes`, `goals`, `integration`, `platform`.

## Repositorios relacionados

- `velora-web` — aplicación web (Next.js + TypeScript).
- `velora-android` — aplicación Android (Kotlin + Jetpack Compose).
