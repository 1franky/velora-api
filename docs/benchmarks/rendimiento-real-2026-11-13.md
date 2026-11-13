# Rendimiento con rodadas reales

11 rodadas reales del propietario (4 FIT, 4 GPX y 3 TCX), importadas por la API completa contra PostgreSQL 18 + PostGIS 3.7 (Testcontainers), en una MacBook Air (Apple Silicon) con Java 25, el 13/11/2026. Los archivos no se versionan; aquí solo hay totales anónimos. Para reproducir con archivos propios:

```bash
./gradlew analyzeLocalFiles -PlocalFiles=/ruta --tests '*LocalPerformanceReport'   # → build/local-data/rendimiento.md
```

## Importación y espacio

| Formato | Archivos | Original (rango) | Importar (rango) | Muestras (rango) | Streams en la BD (rango) |
|---|---|---|---|---|---|
| FIT | 4 | 80–136 KiB | 311–1226 ms | 2946–5076 | 25–43 KiB |
| GPX | 4 | 127–301 KiB | 97–559 ms | 884–2106 | 5–11 KiB |
| TCX | 3 | 1.0–1.4 MiB | 251–814 ms | 3145–4211 | 25–33 KiB |

- **Total:** 11 importaciones en 5.0 s.
- **Deduplicación:** el mismo TCX subido dos veces se detectó en 214 ms como DUPLICATE, por SHA-256.
- **Espacio:** los streams ocupan entre 3 % (TCX) y 40 % (FIT) del archivo original. El original se guarda aparte (ADR-013).
- **Primeras importaciones:** las más lentas, de 0.8 a 1.2 s, son las primeras de la corrida y pagan el arranque en frío de la JVM.

## Lecturas

Mediana y máximo de 15 repeticiones tras calentar, con MockMvc: sin red ni TLS, así que el tiempo real suma la latencia del túnel.

| Pantalla | Mediana (ms) | Máximo (ms) |
|---|---|---|
| Detalle de una rodada | 2.4 | 3.8 |
| Gráficas (streams sin posiciones) | 8.5 | 15.8 |
| Recorrido para el mapa (zoom 14) | 20.8 | 26.7 |
| Subidas | 4.0 | 7.2 |
| **Caminos nuevos** | **532.8** | **610.4** |
| Historial (primera página) | 2.5 | 17.6 |
| Inicio (mes / todo) | 3.9 / 3.3 | 5.8 / 5.4 |
| Rendimiento: tendencias, récords, Eddington, zonas, carga | 2.5–4.1 | 2.9–6.9 |
| Explorador: resumen de celdas | 3.4 | 9.9 |

## Conclusiones

- Todo responde en menos de 25 ms, salvo **Caminos nuevos**. Ese cálculo compara la rodada contra todas las demás al consultar (ADR-019), y su costo crece con el historial. Hoy, ~0.5 s es aceptable para una sección que carga aparte. Si el historial lo vuelve lento, la siguiente medida es calcularlo al importar y guardarlo, como las celdas de exploración (ADR-018).
- Con 3,000 rodadas sintéticas, la vista `counted_activities` respondía en ~30 ms (Fase 5). Los agregados de Inicio y Rendimiento no deberían ser problema en años de uso.
- El códec de streams (ADR-012) se confirma con datos reales: lecturas de pocos milisegundos y una fracción del tamaño original.
