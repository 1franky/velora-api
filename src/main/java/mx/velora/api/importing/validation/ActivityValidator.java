package mx.velora.api.importing.validation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.ImportedActivity;
import mx.velora.api.importing.ImportedLap;
import mx.velora.api.importing.RejectionReason;
import mx.velora.api.importing.ReportedSummary;
import mx.velora.api.importing.TrackPoint;
import mx.velora.api.importing.ValidatedActivity;
import mx.velora.api.importing.ValidationWarning;
import mx.velora.api.importing.ValidationWarning.Code;
import org.jspecify.annotations.Nullable;

/**
 * Valida y sanea una actividad recién parseada ({@code 05-import-pipeline.md}, sección 3).
 *
 * <p>Rechaza ({@link RejectionReason#INVALID_DATA}) lo que no puede representar una actividad: sin muestras, sin
 * ninguna marca de tiempo o con tiempos fuera de [{@link #EARLIEST_PLAUSIBLE}, ahora + {@link #FUTURE_TOLERANCE}].
 *
 * <p>Corrige con advertencia lo recuperable: coordenadas fuera de rango o en (0,0), saltos de GPS imposibles,
 * distancias, velocidades y tiempos negativos. Las correcciones eliminan el valor dañado (queda {@code null}); nunca
 * lo sustituyen por otro. Las marcas de tiempo que retroceden solo generan advertencia.
 */
public final class ActivityValidator {

    public static final String VERSION = "import-validation/2+" + GpsJumpDetector.VERSION + "+"
            + AltitudeJumpDetector.VERSION + "+" + FootprintCalculator.VERSION;

    /** Pausa a partir de la cual se advierte (p. ej. actividades reanudadas días después). */
    static final Duration LONG_PAUSE = Duration.ofHours(6);

    /** Anterior a cualquier dispositivo GPS de consumo (y a la época FIT, 31/12/1989). */
    static final Instant EARLIEST_PLAUSIBLE = Instant.parse("1990-01-01T00:00:00Z");

    /** Margen para relojes de dispositivo ligeramente adelantados. */
    static final Duration FUTURE_TOLERANCE = Duration.ofDays(1);

    private final Clock clock;

    public ActivityValidator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ValidatedActivity validate(ImportedActivity activity) {
        if (activity.points().isEmpty()) {
            throw invalid("el archivo no contiene muestras de actividad");
        }
        var counts = new EnumMap<Code, Integer>(Code.class);
        checkTimes(activity.points(), counts);

        var points = new ArrayList<TrackPoint>(activity.points().size());
        for (var point : activity.points()) {
            points.add(sanitize(point, counts));
        }
        var jumps = GpsJumpDetector.detect(points);
        jumps.stream().forEach(i -> points.set(i, withoutPosition(points.get(i))));
        add(counts, Code.GPS_JUMPS, jumps.cardinality());
        var altitudeJumps = AltitudeJumpDetector.detect(points);
        altitudeJumps.stream().forEach(i -> points.set(i, withoutAltitude(points.get(i))));
        add(counts, Code.ALTITUDE_JUMPS, altitudeJumps.cardinality());

        var laps = activity.laps().stream().map(lap -> sanitize(lap, counts)).toList();

        var sanitized = new ImportedActivity(
                activity.format(),
                activity.parser(),
                activity.sourceActivityKey(),
                activity.title(),
                activity.startedAt(),
                laps,
                points,
                sanitize(activity.reported(), counts));
        return new ValidatedActivity(sanitized, warnings(counts), FootprintCalculator.compute(sanitized), VERSION);
    }

    private void checkTimes(List<TrackPoint> points, Map<Code, Integer> counts) {
        var latest = clock.instant().plus(FUTURE_TOLERANCE);
        Instant previous = null;
        boolean anyTime = false;
        for (var point : points) {
            var time = point.time();
            if (time == null) {
                continue;
            }
            anyTime = true;
            if (time.isBefore(EARLIEST_PLAUSIBLE) || time.isAfter(latest)) {
                throw invalid("el archivo contiene marcas de tiempo fuera de un rango plausible");
            }
            if (previous != null && time.isBefore(previous)) {
                add(counts, Code.NON_MONOTONIC_TIME, 1);
            }
            if (previous != null && Duration.between(previous, time).compareTo(LONG_PAUSE) >= 0) {
                add(counts, Code.LONG_PAUSES, 1);
            }
            previous = time;
        }
        if (!anyTime) {
            throw invalid("el archivo no contiene marcas de tiempo");
        }
    }

    private static TrackPoint sanitize(TrackPoint p, Map<Code, Integer> counts) {
        var latitude = p.latitude();
        var longitude = p.longitude();
        if (p.hasPosition() && !isValidPosition(latitude, longitude)) {
            add(counts, Code.INVALID_COORDINATES, 1);
            latitude = null;
            longitude = null;
        }
        var distance = nonNegative(p.distanceMeters());
        var speed = nonNegative(p.speedMps());
        if (distance != p.distanceMeters() || speed != p.speedMps()) {
            add(counts, Code.NEGATIVE_VALUES, 1);
        }
        return new TrackPoint(
                p.segment(),
                p.time(),
                latitude,
                longitude,
                p.altitudeMeters(),
                distance,
                p.heartRateBpm(),
                p.cadenceRpm(),
                p.powerWatts(),
                speed,
                p.temperatureCelsius());
    }

    private static ImportedLap sanitize(ImportedLap l, Map<Code, Integer> counts) {
        var elapsed = nonNegative(l.elapsedTimeSeconds());
        var timer = nonNegative(l.timerTimeSeconds());
        var distance = nonNegative(l.distanceMeters());
        var avgSpeed = nonNegative(l.averageSpeedMps());
        var maxSpeed = nonNegative(l.maxSpeedMps());
        if (elapsed != l.elapsedTimeSeconds()
                || timer != l.timerTimeSeconds()
                || distance != l.distanceMeters()
                || avgSpeed != l.averageSpeedMps()
                || maxSpeed != l.maxSpeedMps()) {
            add(counts, Code.NEGATIVE_VALUES, 1);
        }
        return new ImportedLap(
                l.lapNumber(),
                l.startedAt(),
                elapsed,
                timer,
                distance,
                avgSpeed,
                maxSpeed,
                l.averageHeartRateBpm(),
                l.maxHeartRateBpm(),
                l.averageCadenceRpm(),
                l.averagePowerWatts(),
                l.maxPowerWatts());
    }

    private static ReportedSummary sanitize(ReportedSummary r, Map<Code, Integer> counts) {
        var elapsed = nonNegative(r.elapsedTimeSeconds());
        var timer = nonNegative(r.timerTimeSeconds());
        var distance = nonNegative(r.distanceMeters());
        var ascent = nonNegative(r.ascentMeters());
        var descent = nonNegative(r.descentMeters());
        var avgSpeed = nonNegative(r.averageSpeedMps());
        var maxSpeed = nonNegative(r.maxSpeedMps());
        if (elapsed != r.elapsedTimeSeconds()
                || timer != r.timerTimeSeconds()
                || distance != r.distanceMeters()
                || ascent != r.ascentMeters()
                || descent != r.descentMeters()
                || avgSpeed != r.averageSpeedMps()
                || maxSpeed != r.maxSpeedMps()) {
            add(counts, Code.NEGATIVE_VALUES, 1);
        }
        return new ReportedSummary(
                r.sport(),
                r.subSport(),
                elapsed,
                timer,
                distance,
                ascent,
                descent,
                avgSpeed,
                maxSpeed,
                r.averageHeartRateBpm(),
                r.maxHeartRateBpm(),
                r.averageCadenceRpm(),
                r.averagePowerWatts(),
                r.maxPowerWatts());
    }

    private static TrackPoint withoutAltitude(TrackPoint p) {
        return new TrackPoint(
                p.segment(),
                p.time(),
                p.latitude(),
                p.longitude(),
                null,
                p.distanceMeters(),
                p.heartRateBpm(),
                p.cadenceRpm(),
                p.powerWatts(),
                p.speedMps(),
                p.temperatureCelsius());
    }

    private static TrackPoint withoutPosition(TrackPoint p) {
        return new TrackPoint(
                p.segment(),
                p.time(),
                null,
                null,
                p.altitudeMeters(),
                p.distanceMeters(),
                p.heartRateBpm(),
                p.cadenceRpm(),
                p.powerWatts(),
                p.speedMps(),
                p.temperatureCelsius());
    }

    private static boolean isValidPosition(Double latitude, Double longitude) {
        return Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90
                && latitude <= 90
                && longitude >= -180
                && longitude <= 180
                // (0,0) es el valor típico de un receptor sin fix, no una ubicación real de rodada.
                && !(latitude == 0.0 && longitude == 0.0);
    }

    /** Devuelve la misma instancia si el valor es válido, para detectar cambios por identidad. */
    private static @Nullable Double nonNegative(@Nullable Double value) {
        return value != null && value < 0 ? null : value;
    }

    private static void add(Map<Code, Integer> counts, Code code, int amount) {
        if (amount > 0) {
            counts.merge(code, amount, Integer::sum);
        }
    }

    private static List<ValidationWarning> warnings(Map<Code, Integer> counts) {
        return counts.entrySet().stream()
                .map(entry -> new ValidationWarning(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static ImportRejectedException invalid(String detail) {
        return new ImportRejectedException(RejectionReason.INVALID_DATA, detail);
    }
}
