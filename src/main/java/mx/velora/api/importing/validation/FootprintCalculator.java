package mx.velora.api.importing.validation;

import java.time.Duration;
import java.time.Instant;
import mx.velora.api.importing.ActivityFootprint;
import mx.velora.api.importing.BoundingBox;
import mx.velora.api.importing.ImportedActivity;
import mx.velora.api.importing.Measurement;
import mx.velora.api.importing.MetricProvenance;
import mx.velora.api.importing.TrackPoint;
import org.jspecify.annotations.Nullable;

/**
 * Calcula la huella de una actividad ya saneada.
 *
 * <p>Distancia: la mayor distancia acumulada informada por el dispositivo; si no existe, la suma haversine entre
 * posiciones consecutivas dentro de cada segmento (no se suma el desplazamiento durante pausas).
 */
final class FootprintCalculator {

    static final String VERSION = "footprint/1";

    private FootprintCalculator() {}

    /** Requiere al menos una muestra con tiempo; {@link ActivityValidator} lo garantiza. */
    static ActivityFootprint compute(ImportedActivity activity) {
        Instant first = null;
        Instant last = null;
        Double deviceDistance = null;
        double derivedDistance = 0;
        int positioned = 0;
        double minLat = Double.POSITIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        TrackPoint previous = null;

        for (var point : activity.points()) {
            if (point.time() != null) {
                first = first == null || point.time().isBefore(first) ? point.time() : first;
                last = last == null || point.time().isAfter(last) ? point.time() : last;
            }
            if (point.distanceMeters() != null) {
                deviceDistance = deviceDistance == null
                        ? point.distanceMeters()
                        : Math.max(deviceDistance, point.distanceMeters());
            }
            if (point.hasPosition()) {
                positioned++;
                minLat = Math.min(minLat, point.latitude());
                maxLat = Math.max(maxLat, point.latitude());
                minLon = Math.min(minLon, point.longitude());
                maxLon = Math.max(maxLon, point.longitude());
                if (previous != null && previous.segment() == point.segment()) {
                    derivedDistance += Geo.distanceMeters(previous, point);
                }
                previous = point;
            }
        }
        if (first == null) {
            throw new IllegalArgumentException("La actividad no tiene muestras con tiempo");
        }

        var elapsed = Duration.between(first, last).toMillis() / 1000.0;
        var bbox = positioned > 0 ? new BoundingBox(minLat, minLon, maxLat, maxLon) : null;
        return new ActivityFootprint(
                first, elapsed, distance(deviceDistance, derivedDistance, positioned), bbox, VERSION);
    }

    private static @Nullable Measurement distance(@Nullable Double device, double derived, int positioned) {
        if (device != null) {
            return new Measurement(device, MetricProvenance.DEVICE_REPORTED);
        }
        return positioned >= 2 ? new Measurement(derived, MetricProvenance.DERIVED) : null;
    }
}
