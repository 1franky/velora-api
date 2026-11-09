package mx.velora.api.importing;

/** Origen de una métrica, expuesto en la API como {@code metricsProvenance} (ver {@code 03-api-design.md}). */
public enum MetricProvenance {
    /** Valor informado por el dispositivo en el archivo. */
    DEVICE_REPORTED,
    /** Valor calculado por Velora; acompaña una versión de algoritmo. */
    DERIVED
}
