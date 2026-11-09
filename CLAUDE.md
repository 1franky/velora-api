# Guía de trabajo para Claude Code

> Copia del archivo homónimo de `velora-project-kit` (carpeta padre). La especificación vive en `../docs/` y el contrato en `../contracts/openapi-v1.yaml`.

Lee primero `AGENTS.md`, `README.md` (de este repo y `../README.md` del kit) y la documentación de la tarea. Respeta estas instrucciones junto con `AGENTS.md`.

## Protocolo

- Explora antes de editar.
- Mantén el alcance de cada sesión acotado.
- Usa el plan de implementación aprobado.
- No asumas que una tarea autorizada implica permiso para desplegar o publicar.
- Prefiere cambios pequeños, tipados y cubiertos por pruebas.
- Explica riesgos, decisiones y archivos modificados al terminar.

## Trabajo multiagente

Si se usan subagentes:
- asigna módulos o tareas sin solapamiento;
- define contratos antes de paralelizar;
- evita que varios agentes modifiquen la misma migración o archivo de configuración;
- integra cambios mediante revisión y pruebas;
- no compartas secretos ni archivos privados de actividades.

## Restricción crítica

El conector Strava permanece deshabilitado por defecto. Revisa `../docs/09-strava-legal-and-data-governance.md` y exige una decisión documentada antes de usar datos de Strava en analítica, persistencia o funcionalidades geoespaciales.
