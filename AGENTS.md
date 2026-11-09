# Instrucciones para agentes de desarrollo — Velora

> Copia del archivo homónimo de `velora-project-kit` (carpeta padre). La especificación vive en `../docs/` y el contrato en `../contracts/openapi-v1.yaml`.

## Antes de cambiar código

1. Lee `README.md` (de este repo y `../README.md` del kit) y los documentos relevantes de `../docs/`.
2. Identifica el repositorio y módulo donde corresponde el cambio.
3. Revisa el estado de Git y no sobrescribas trabajo ajeno.
4. No inventes requisitos, endpoints, campos ni dependencias.
5. Si hay contradicción entre código y documentación, informa la discrepancia y propone una decisión; no hagas una migración silenciosa.

## Reglas de producto y localización

- Todo texto visible al usuario debe estar en español de México (`es-MX`).
- Usa `America/Mexico_City` como zona horaria inicial, pero permite configurarla.
- Fechas visibles: `dd/MM/yyyy`; persistencia de instantes en UTC (`timestamptz`).
- Unidades por defecto: km, m, km/h, °C.
- Usa términos naturales en México: rodada, recorrido, bicicleta, MTB, ascenso, descenso, desnivel, frecuencia cardíaca.
- Evita traducir identificadores técnicos, nombres de clases, variables, paquetes, endpoints y commits.
- No hardcodees textos de interfaz: usa el sistema de internacionalización, aunque inicialmente solo exista `es-MX`.

## Reglas de arquitectura

- Backend: Java + Spring Boot, arquitectura modular monolith (ver ADR-007).
- Base de datos: PostgreSQL + PostGIS; migraciones versionadas con Flyway.
- Web: Next.js + TypeScript.
- Android: Kotlin + Jetpack Compose.
- Contrato HTTP: OpenAPI versionado.
- Mantén separación entre dominio, aplicación, infraestructura y presentación.
- Evita dependencias circulares entre módulos.
- No extraigas microservicios hasta que exista una necesidad medida y documentada.
- Los cálculos derivados deben registrar `algorithm_version`.
- Conserva por separado métricas originales y métricas calculadas.
- Las tareas de importación y geoprocesamiento deben ejecutarse de forma asíncrona e idempotente.

## Datos y privacidad

- No registres tokens, contraseñas, archivos FIT/GPX/TCX ni coordenadas privadas en logs.
- No incluyas secretos en código, fixtures ni capturas.
- Valida tamaño, formato y contenido de archivos importados.
- No publiques mapas ni tracks privados.
- Diseña borrado y exportación de datos desde el inicio.
- No proceses Strava Data con el motor analítico salvo que el caso de uso esté expresamente permitido y validado contra el acuerdo vigente. La implementación debe estar deshabilitada por defecto.
- No uses scraping de Strava ni automatices descargas desde su interfaz.
- No copies marca, logotipo, diseño visual ni textos de Strava.

## Calidad

- Añade pruebas unitarias para reglas de dominio y algoritmos.
- Añade pruebas de integración para persistencia, PostGIS y API.
- Añade pruebas de contrato para OpenAPI.
- Añade pruebas de importación con fixtures sintéticos o datos propios autorizados y anonimizados.
- Prueba límites, datos faltantes, GPS ruidoso, cruces de medianoche y zonas horarias.
- Ejecuta formatter, linter y pruebas relevantes antes de finalizar.
- No afirmes que una prueba pasó si no la ejecutaste.
- No agregues dependencias sin explicar su propósito y costo.

## Forma de trabajo

- Entrega cambios pequeños y verificables.
- Antes de una tarea grande, presenta plan de archivos y riesgos.
- Actualiza documentación y ADRs cuando cambien contratos o decisiones.
- Nunca hagas `git push`, publiques secretos o despliegues a producción sin autorización explícita.

## Reglas específicas de `velora-api`

- Separa dominio, aplicación, infraestructura y presentación; sin dependencias circulares entre módulos.
- Los módulos no acceden a repositorios internos de otros módulos; usa interfaces/casos de uso.
- Persistencia en unidades SI (m, s, m/s) e instantes como `timestamptz`.
- Los cálculos derivados registran `algorithm_version`; conserva métricas originales y calculadas por separado.
- Importación y geoprocesamiento asíncronos e idempotentes.
- Pruebas de integración para persistencia, PostGIS y API; pruebas de contrato para OpenAPI.
