package mx.velora.api.activity;

/**
 * Métricas de resumen de una actividad. Cada una puede guardarse con origen {@code DEVICE_REPORTED} y
 * {@code DERIVED}; el valor efectivo prefiere el del dispositivo.
 */
public enum Metric {
    ELAPSED_TIME("elapsedTimeSeconds"),
    /** Tiempo en movimiento; del dispositivo es el tiempo con cronómetro activo. */
    MOVING_TIME("movingTimeSeconds"),
    DISTANCE("distanceMeters"),
    ELEVATION_GAIN("elevationGainMeters"),
    ELEVATION_LOSS("elevationLossMeters"),
    AVERAGE_SPEED("averageSpeedMps"),
    MAX_SPEED("maxSpeedMps"),
    AVERAGE_HEART_RATE("averageHeartRateBpm"),
    MAX_HEART_RATE("maxHeartRateBpm"),
    AVERAGE_CADENCE("averageCadenceRpm"),
    AVERAGE_POWER("averagePowerWatts"),
    MAX_POWER("maxPowerWatts");

    private final String apiName;

    Metric(String apiName) {
        this.apiName = apiName;
    }

    /** Nombre del campo en la API y en {@code metricsProvenance}. */
    public String apiName() {
        return apiName;
    }

    public enum Source {
        DEVICE_REPORTED,
        DERIVED
    }
}
