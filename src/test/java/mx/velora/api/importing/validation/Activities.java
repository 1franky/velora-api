package mx.velora.api.importing.validation;

import java.time.Instant;
import java.util.List;
import mx.velora.api.importing.FileFormat;
import mx.velora.api.importing.ImportedActivity;
import mx.velora.api.importing.ImportedLap;
import mx.velora.api.importing.ParserInfo;
import mx.velora.api.importing.TrackPoint;
import org.jspecify.annotations.Nullable;

/** Construcción concisa de actividades sintéticas para pruebas. */
final class Activities {

    static final Instant START = Instant.parse("2026-11-08T13:15:00Z");

    /** Aproximadamente 1 m en latitud. */
    static final double METER_IN_DEGREES = 1.0 / 111_195.0;

    private Activities() {}

    static ImportedActivity of(List<TrackPoint> points) {
        return of(points, List.of());
    }

    static ImportedActivity of(List<TrackPoint> points, List<ImportedLap> laps) {
        return new ImportedActivity(FileFormat.GPX, new ParserInfo("test", "1"), null, null, START, laps, points);
    }

    /** Punto con posición en el segmento 0, {@code offsetSeconds} después de {@link #START}. */
    static TrackPoint at(int offsetSeconds, double latitude, double longitude) {
        return point(0, offsetSeconds, latitude, longitude, null, null);
    }

    static TrackPoint point(
            int segment,
            @Nullable Integer offsetSeconds,
            @Nullable Double latitude,
            @Nullable Double longitude,
            @Nullable Double distanceMeters,
            @Nullable Double speedMps) {
        return new TrackPoint(
                segment,
                offsetSeconds == null ? null : START.plusSeconds(offsetSeconds),
                latitude,
                longitude,
                null,
                distanceMeters,
                null,
                null,
                null,
                speedMps,
                null);
    }

    /** Punto que avanza hacia el norte {@code metersPerSecond} por segundo desde (19.0, -99.0). */
    static TrackPoint moving(int segment, int offsetSeconds, double metersPerSecond) {
        return point(
                segment, offsetSeconds, 19.0 + offsetSeconds * metersPerSecond * METER_IN_DEGREES, -99.0, null, null);
    }
}
