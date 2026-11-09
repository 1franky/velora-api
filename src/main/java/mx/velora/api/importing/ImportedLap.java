package mx.velora.api.importing;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Vuelta (lap) tal como la reporta el dispositivo, en unidades SI. Los valores ausentes permanecen {@code null}.
 *
 * @param lapNumber número de vuelta, desde 1, en el orden del archivo
 * @param startedAt instante UTC de inicio
 * @param elapsedTimeSeconds tiempo transcurrido total, incluidas pausas
 * @param timerTimeSeconds tiempo con el cronómetro activo (excluye pausas)
 * @param distanceMeters distancia de la vuelta
 * @param averageSpeedMps velocidad media
 * @param maxSpeedMps velocidad máxima
 * @param averageHeartRateBpm frecuencia cardíaca media
 * @param maxHeartRateBpm frecuencia cardíaca máxima
 * @param averageCadenceRpm cadencia media
 * @param averagePowerWatts potencia media
 * @param maxPowerWatts potencia máxima
 */
public record ImportedLap(
        int lapNumber,
        @Nullable Instant startedAt,
        @Nullable Double elapsedTimeSeconds,
        @Nullable Double timerTimeSeconds,
        @Nullable Double distanceMeters,
        @Nullable Double averageSpeedMps,
        @Nullable Double maxSpeedMps,
        @Nullable Integer averageHeartRateBpm,
        @Nullable Integer maxHeartRateBpm,
        @Nullable Integer averageCadenceRpm,
        @Nullable Integer averagePowerWatts,
        @Nullable Integer maxPowerWatts) {

    public ImportedLap {
        if (lapNumber < 1) {
            throw new IllegalArgumentException("lapNumber debe empezar en 1");
        }
    }
}
