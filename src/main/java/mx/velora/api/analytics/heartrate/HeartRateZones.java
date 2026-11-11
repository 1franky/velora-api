package mx.velora.api.analytics.heartrate;

import org.jspecify.annotations.Nullable;

/**
 * Tiempo en zonas cardiacas por porcentaje de la FC máxima del usuario (ADR-020): Z1 50–60 %, Z2 60–70 %, Z3 70–80 %,
 * Z4 80–90 %, Z5 90 % o más; por debajo de 50 % aparte. Cada muestra vale el tiempo hasta la siguiente, como máximo
 * {@value #MAX_SAMPLE_SECONDS} s: una pausa no se cuenta como esfuerzo.
 */
final class HeartRateZones {

    static final String ALGORITHM_VERSION = "hr-zones/1";
    static final double[] LOWER_PERCENT = {50, 60, 70, 80, 90};
    static final double MAX_SAMPLE_SECONDS = 10;

    private HeartRateZones() {}

    /** Límite inferior de cada zona en ppm (redondeado). */
    static int[] lowerBpm(int maxHeartRate) {
        var result = new int[LOWER_PERCENT.length];
        for (int z = 0; z < result.length; z++) {
            result[z] = (int) Math.round(maxHeartRate * LOWER_PERCENT[z] / 100);
        }
        return result;
    }

    /**
     * Suma en {@code seconds} (índice 0: por debajo de Z1; 1–5: zonas) el tiempo de una actividad. Devuelve
     * {@code false} si no tiene muestras de FC con tiempo.
     */
    static boolean accumulate(
            @Nullable Double[] time, @Nullable Double[] heartRate, int maxHeartRate, double[] seconds) {
        var lower = lowerBpm(maxHeartRate);
        var n = Math.min(time.length, heartRate.length);
        var counted = false;
        for (int i = 0; i + 1 < n; i++) {
            if (time[i] == null || time[i + 1] == null || heartRate[i] == null || heartRate[i] <= 0) {
                continue;
            }
            var dt = time[i + 1] - time[i];
            if (dt <= 0) {
                continue;
            }
            var zone = 0;
            while (zone < lower.length && heartRate[i] >= lower[zone]) {
                zone++;
            }
            seconds[zone] += Math.min(dt, MAX_SAMPLE_SECONDS);
            counted = true;
        }
        return counted;
    }
}
