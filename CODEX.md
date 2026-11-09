# Guía de trabajo para Codex

> Copia del archivo homónimo de `velora-project-kit` (carpeta padre). La especificación vive en `../docs/` y el contrato en `../contracts/openapi-v1.yaml`.

Lee primero `AGENTS.md` y `README.md` (de este repo y `../README.md` del kit). Después, consulta los documentos de `../docs/` correspondientes a la tarea.

## Modo de ejecución recomendado

1. Inspecciona el repositorio, ramas, archivos y pruebas existentes.
2. Resume el estado actual y las diferencias respecto a la especificación.
3. Propón un plan breve con archivos a modificar y pruebas.
4. Implementa una sola unidad vertical por tarea.
5. Ejecuta pruebas y reporta comandos/resultados reales.
6. Actualiza documentación cuando cambie el contrato o una decisión.

## Límites

- No implementes el conector Strava ni importes Strava Data hasta completar la revisión descrita en `../docs/09-strava-legal-and-data-governance.md`.
- No cambies la arquitectura modular monolith a microservicios.
- No inventes credenciales, URLs productivas ni secretos.
- No borres datos ni ejecutes migraciones destructivas sin autorización.
- No generes toda la aplicación en un único cambio.

## Primera tarea sugerida

Realiza un análisis de viabilidad técnica del MVP de importación FIT/GPX/TCX. Entrega:
- propuesta de módulos backend;
- librerías candidatas con licencias;
- diseño de `ImportJob`;
- estrategia de deduplicación;
- fixtures de prueba;
- plan de implementación por PR.

No escribas aún el conector Strava.
