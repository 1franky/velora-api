# Instrucciones para agentes — velora-api

Repositorio del backend (Kotlin + Spring Boot, modular monolith, PostgreSQL + PostGIS, Flyway).

## Reglas específicas

- Separa dominio, aplicación, infraestructura y presentación; sin dependencias circulares entre módulos.
- Los módulos no acceden a repositorios internos de otros módulos; usa interfaces/casos de uso.
- Persistencia en unidades SI (m, s, m/s) e instantes como `timestamptz`.
- Los cálculos derivados registran `algorithm_version`; conserva métricas originales y calculadas por separado.
- Importación y geoprocesamiento asíncronos e idempotentes.
- Pruebas de integración para persistencia, PostGIS y API; pruebas de contrato para OpenAPI.

## Reglas comunes (resumen de `velora-project-kit/AGENTS.md`)

- Lee la especificación en `velora-project-kit` (`README.md`, `AGENTS.md`, `docs/`) antes de cambiar código.
- No inventes requisitos, endpoints, campos ni dependencias; el contrato HTTP es `contracts/openapi-v1.yaml`.
- Todo texto visible al usuario en español de México (`es-MX`), mediante el sistema de i18n; nunca hardcodeado.
- Zona horaria inicial `America/Mexico_City`, configurable. Fechas visibles `dd/MM/yyyy`; instantes en UTC.
- Unidades por defecto: km, m, km/h, °C.
- No registres tokens, contraseñas, archivos FIT/GPX/TCX ni coordenadas privadas en logs.
- No incluyas secretos en código, fixtures ni capturas. Este repositorio es **público**.
- El conector Strava permanece deshabilitado por defecto; ver `docs/09-strava-legal-and-data-governance.md`.
- Cambios pequeños, tipados y con pruebas. No afirmes que una prueba pasó si no la ejecutaste.
- Nunca hagas `git push` a ramas protegidas, publiques secretos ni despliegues sin autorización explícita.
