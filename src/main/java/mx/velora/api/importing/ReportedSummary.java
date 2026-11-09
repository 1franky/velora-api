package mx.velora.api.importing;

import org.jspecify.annotations.Nullable;

/**
 * Totales que el dispositivo informa en el archivo (métricas {@code DEVICE_REPORTED}), en unidades SI. Los valores
 * ausentes permanecen {@code null}; las métricas calculadas por Velora van aparte, en {@link DerivedMetrics}.
 *
 * @param sport deporte tal como lo nombra el formato (FIT {@code session.sport}, TCX {@code Activity@Sport})
 * @param subSport subdeporte FIT ({@code session.sub_sport}), p. ej. {@code ROAD}, {@code MOUNTAIN}
 * @param elapsedTimeSeconds tiempo transcurrido, pausas incluidas
 * @param timerTimeSeconds tiempo con el cronómetro activo
 * @param distanceMeters distancia total
 * @param ascentMeters desnivel positivo
 * @param descentMeters desnivel negativo
 * @param averageSpeedMps velocidad media
 * @param maxSpeedMps velocidad máxima
 * @param averageHeartRateBpm frecuencia cardíaca media
 * @param maxHeartRateBpm frecuencia cardíaca máxima
 * @param averageCadenceRpm cadencia media
 * @param averagePowerWatts potencia media
 * @param maxPowerWatts potencia máxima
 */
public record ReportedSummary(
        @Nullable String sport,
        @Nullable String subSport,
        @Nullable Double elapsedTimeSeconds,
        @Nullable Double timerTimeSeconds,
        @Nullable Double distanceMeters,
        @Nullable Double ascentMeters,
        @Nullable Double descentMeters,
        @Nullable Double averageSpeedMps,
        @Nullable Double maxSpeedMps,
        @Nullable Integer averageHeartRateBpm,
        @Nullable Integer maxHeartRateBpm,
        @Nullable Integer averageCadenceRpm,
        @Nullable Integer averagePowerWatts,
        @Nullable Integer maxPowerWatts) {

    /** Sin datos del dispositivo (p. ej. GPX). */
    public static final ReportedSummary EMPTY =
            new ReportedSummary(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
}
