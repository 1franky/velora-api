package mx.velora.api.importing;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Métricas calculadas por Velora a partir de las muestras (métricas {@code DERIVED}), en unidades SI. Se guardan
 * aparte de las informadas por el dispositivo ({@link ReportedSummary}); un valor sin datos suficientes queda en
 * {@code null}.
 *
 * @param elapsedTimeSeconds de la primera a la última muestra con tiempo, pausas incluidas
 * @param movingTimeSeconds suma de intervalos en movimiento
 * @param distanceMeters suma haversine dentro de cada segmento; {@code null} sin posiciones
 * @param ascentMeters desnivel positivo con histéresis
 * @param descentMeters desnivel negativo con histéresis
 * @param averageSpeedMps distancia entre tiempo en movimiento
 * @param maxSpeedMps máxima velocidad sostenida en la ventana configurada
 * @param averageHeartRateBpm media ponderada por tiempo en movimiento
 * @param maxHeartRateBpm máximo
 * @param averageCadenceRpm media ponderada en movimiento, sin ceros (pedaleo efectivo)
 * @param averagePowerWatts media ponderada en movimiento, con ceros
 * @param maxPowerWatts máximo
 * @param parameters parámetros usados
 * @param algorithmVersion versión del cálculo
 */
public record DerivedMetrics(
        double elapsedTimeSeconds,
        double movingTimeSeconds,
        @Nullable Double distanceMeters,
        @Nullable Double ascentMeters,
        @Nullable Double descentMeters,
        @Nullable Double averageSpeedMps,
        @Nullable Double maxSpeedMps,
        @Nullable Integer averageHeartRateBpm,
        @Nullable Integer maxHeartRateBpm,
        @Nullable Integer averageCadenceRpm,
        @Nullable Integer averagePowerWatts,
        @Nullable Integer maxPowerWatts,
        Parameters parameters,
        String algorithmVersion) {

    public DerivedMetrics {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(algorithmVersion, "algorithmVersion");
    }

    /**
     * @param movingSpeedThresholdMps velocidad mínima para contar un intervalo como en movimiento
     * @param maxMovingIntervalSeconds un intervalo más largo sin cambio de segmento se considera pausa
     * @param elevationHysteresisMeters cambio de altitud mínimo para acumular desnivel
     * @param maxSpeedWindowSeconds ventana mínima para la velocidad máxima (filtra ruido de GPS)
     */
    public record Parameters(
            double movingSpeedThresholdMps,
            double maxMovingIntervalSeconds,
            double elevationHysteresisMeters,
            double maxSpeedWindowSeconds) {

        /**
         * Calibrado con rodadas reales del propietario (09/11/2026, solo en local): 1 m/s dejó el movimiento de GPX y
         * TCX a −0,4 % del cronómetro del dispositivo; ventana de 10 s, velocidad máxima a ±0,7 km/h de la
         * reportada; histéresis de 1 m, desnivel a 16 % de media del reportado por FIT (se prefiere el del
         * dispositivo cuando existe).
         */
        public static final Parameters DEFAULT = new Parameters(1.0, 300, 1.0, 10);
    }
}
