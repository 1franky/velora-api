package mx.velora.api.importing;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Muestra de una actividad tal como la reporta el archivo, en unidades SI.
 *
 * <p>Los valores ausentes en el archivo permanecen {@code null}; nunca se sustituyen por cero.
 *
 * @param segment índice del segmento del track (0 en adelante); un cambio de segmento suele indicar una pausa
 * @param time instante UTC de la muestra
 * @param latitude grados WGS84
 * @param longitude grados WGS84
 * @param altitudeMeters altitud en metros
 * @param distanceMeters distancia acumulada en metros reportada por el dispositivo
 * @param heartRateBpm frecuencia cardíaca en latidos por minuto
 * @param cadenceRpm cadencia en revoluciones por minuto
 * @param powerWatts potencia en watts
 * @param speedMps velocidad en m/s reportada por el dispositivo
 * @param temperatureCelsius temperatura en °C
 */
public record TrackPoint(
        int segment,
        @Nullable Instant time,
        @Nullable Double latitude,
        @Nullable Double longitude,
        @Nullable Double altitudeMeters,
        @Nullable Double distanceMeters,
        @Nullable Integer heartRateBpm,
        @Nullable Integer cadenceRpm,
        @Nullable Integer powerWatts,
        @Nullable Double speedMps,
        @Nullable Double temperatureCelsius) {

    public boolean hasPosition() {
        return latitude != null && longitude != null;
    }
}
