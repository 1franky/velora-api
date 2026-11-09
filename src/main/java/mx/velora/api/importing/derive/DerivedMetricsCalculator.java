package mx.velora.api.importing.derive;

import java.time.Duration;
import java.util.List;
import mx.velora.api.importing.DerivedMetrics;
import mx.velora.api.importing.DerivedMetrics.Parameters;
import mx.velora.api.importing.ImportedActivity;
import mx.velora.api.importing.TrackPoint;
import org.jspecify.annotations.Nullable;

/**
 * Calcula {@link DerivedMetrics} (algoritmo {@value #VERSION}).
 *
 * <ul>
 *   <li><b>Movimiento:</b> un intervalo entre muestras consecutivas del mismo segmento cuenta si su velocidad es al
 *       menos {@code movingSpeedThresholdMps} y dura como máximo {@code maxMovingIntervalSeconds}. La velocidad del
 *       intervalo sale, por prioridad, de la distancia del dispositivo, de las posiciones o de la velocidad
 *       informada; si no hay ninguna (rodillo sin sensor), el intervalo cuenta como movimiento.
 *   <li><b>Desnivel:</b> histéresis sobre la secuencia de altitudes: solo se acumula cuando la altitud se aleja al
 *       menos {@code elevationHysteresisMeters} del último punto de referencia. Filtra el ruido sin suavizar datos.
 *   <li><b>Velocidad máxima:</b> la mayor velocidad media en ventanas de al menos {@code maxSpeedWindowSeconds}.
 *   <li><b>Medias:</b> ponderadas por la duración de los intervalos en movimiento.
 * </ul>
 */
public final class DerivedMetricsCalculator {

    public static final String VERSION = "derived-metrics/1";

    private DerivedMetricsCalculator() {}

    /** Requiere al menos una muestra con tiempo; el validador lo garantiza. */
    public static DerivedMetrics compute(ImportedActivity activity, Parameters parameters) {
        var points = activity.points();
        var stats = new Accumulator(parameters);
        TrackPoint previous = null;
        for (var point : points) {
            if (point.time() != null) {
                if (previous != null && previous.segment() == point.segment()) {
                    stats.interval(previous, point);
                }
                previous = point;
            }
        }
        var distance = haversineDistance(points);
        var effectiveDistance = distance != null ? distance : maxDeviceDistance(points);
        var elevation = elevation(points, parameters.elevationHysteresisMeters());
        return new DerivedMetrics(
                elapsedSeconds(points),
                stats.movingSeconds,
                distance,
                elevation == null ? null : elevation[0],
                elevation == null ? null : elevation[1],
                effectiveDistance == null || stats.movingSeconds <= 0 ? null : effectiveDistance / stats.movingSeconds,
                maxSustainedSpeed(points, parameters.maxSpeedWindowSeconds()),
                stats.heartRate.average(),
                maxInteger(points, TrackPoint::heartRateBpm),
                stats.cadence.average(),
                stats.power.average(),
                maxInteger(points, TrackPoint::powerWatts),
                parameters,
                VERSION);
    }

    /** Acumula tiempo en movimiento y medias ponderadas. */
    private static final class Accumulator {
        private final Parameters parameters;
        private double movingSeconds;
        private final WeightedMean heartRate = new WeightedMean();
        private final WeightedMean cadence = new WeightedMean();
        private final WeightedMean power = new WeightedMean();

        private Accumulator(Parameters parameters) {
            this.parameters = parameters;
        }

        private void interval(TrackPoint from, TrackPoint to) {
            var seconds = Duration.between(from.time(), to.time()).toMillis() / 1000.0;
            if (seconds <= 0 || seconds > parameters.maxMovingIntervalSeconds()) {
                return;
            }
            var speed = intervalSpeed(from, to, seconds);
            if (speed != null && speed < parameters.movingSpeedThresholdMps()) {
                return;
            }
            movingSeconds += seconds;
            heartRate.add(from.heartRateBpm(), seconds);
            power.add(from.powerWatts(), seconds);
            if (from.cadenceRpm() != null && from.cadenceRpm() > 0) {
                cadence.add(from.cadenceRpm(), seconds);
            }
        }
    }

    private static final class WeightedMean {
        private double sum;
        private double weight;

        void add(@Nullable Integer value, double seconds) {
            if (value != null) {
                sum += value * seconds;
                weight += seconds;
            }
        }

        @Nullable Integer average() {
            return weight > 0 ? (int) Math.round(sum / weight) : null;
        }
    }

    private static @Nullable Double intervalSpeed(TrackPoint from, TrackPoint to, double seconds) {
        if (from.distanceMeters() != null
                && to.distanceMeters() != null
                && to.distanceMeters() >= from.distanceMeters()) {
            return (to.distanceMeters() - from.distanceMeters()) / seconds;
        }
        if (from.hasPosition() && to.hasPosition()) {
            return Geo.distanceMeters(from, to) / seconds;
        }
        if (from.speedMps() != null || to.speedMps() != null) {
            var a = from.speedMps() != null ? from.speedMps() : to.speedMps();
            var b = to.speedMps() != null ? to.speedMps() : from.speedMps();
            return (a + b) / 2;
        }
        return null;
    }

    private static double elapsedSeconds(List<TrackPoint> points) {
        java.time.Instant first = null;
        java.time.Instant last = null;
        for (var point : points) {
            if (point.time() != null) {
                first = first == null || point.time().isBefore(first) ? point.time() : first;
                last = last == null || point.time().isAfter(last) ? point.time() : last;
            }
        }
        return first == null ? 0 : Duration.between(first, last).toMillis() / 1000.0;
    }

    /** Suma haversine entre posiciones consecutivas del mismo segmento. */
    static @Nullable Double haversineDistance(List<TrackPoint> points) {
        double total = 0;
        int positioned = 0;
        TrackPoint previous = null;
        for (var point : points) {
            if (!point.hasPosition()) {
                continue;
            }
            positioned++;
            if (previous != null && previous.segment() == point.segment()) {
                total += Geo.distanceMeters(previous, point);
            }
            previous = point;
        }
        return positioned >= 2 ? total : null;
    }

    private static @Nullable Double maxDeviceDistance(List<TrackPoint> points) {
        return points.stream()
                .map(TrackPoint::distanceMeters)
                .filter(java.util.Objects::nonNull)
                .max(Double::compare)
                .orElse(null);
    }

    /** [ascenso, descenso] con histéresis; {@code null} sin altitudes. */
    static double @Nullable [] elevation(List<TrackPoint> points, double hysteresis) {
        Double anchor = null;
        double gain = 0;
        double loss = 0;
        for (var point : points) {
            var altitude = point.altitudeMeters();
            if (altitude == null) {
                continue;
            }
            if (anchor == null) {
                anchor = altitude;
            } else if (altitude - anchor >= hysteresis) {
                gain += altitude - anchor;
                anchor = altitude;
            } else if (anchor - altitude >= hysteresis) {
                loss += anchor - altitude;
                anchor = altitude;
            }
        }
        return anchor == null ? null : new double[] {gain, loss};
    }

    /**
     * Mayor velocidad media en ventanas de al menos {@code windowSeconds} dentro de un segmento. Usa la distancia
     * acumulada del dispositivo si todas las muestras con tiempo la tienen; si no, la acumulada de las posiciones.
     */
    static @Nullable Double maxSustainedSpeed(List<TrackPoint> points, double windowSeconds) {
        var timed = points.stream().filter(p -> p.time() != null).toList();
        var useDevice = !timed.isEmpty() && timed.stream().allMatch(p -> p.distanceMeters() != null);
        var cumulative = new double[timed.size()];
        var known = new boolean[timed.size()];
        TrackPoint lastPositioned = null;
        double accumulated = 0;
        for (int i = 0; i < timed.size(); i++) {
            var p = timed.get(i);
            if (useDevice) {
                cumulative[i] = p.distanceMeters();
                known[i] = true;
            } else if (p.hasPosition()) {
                if (lastPositioned != null && lastPositioned.segment() == p.segment()) {
                    accumulated += Geo.distanceMeters(lastPositioned, p);
                }
                lastPositioned = p;
                cumulative[i] = accumulated;
                known[i] = true;
            }
        }
        Double best = null;
        for (int i = 0, j = 1; i < timed.size(); i++) {
            if (!known[i]) {
                continue;
            }
            j = Math.max(j, i + 1);
            while (j < timed.size()
                    && timed.get(j).segment() == timed.get(i).segment()
                    && (!known[j] || seconds(timed.get(i), timed.get(j)) < windowSeconds)) {
                j++;
            }
            if (j >= timed.size() || timed.get(j).segment() != timed.get(i).segment()) {
                continue;
            }
            var speed = (cumulative[j] - cumulative[i]) / seconds(timed.get(i), timed.get(j));
            best = best == null || speed > best ? speed : best;
        }
        return best;
    }

    private static double seconds(TrackPoint from, TrackPoint to) {
        return Duration.between(from.time(), to.time()).toMillis() / 1000.0;
    }

    private static @Nullable Integer maxInteger(
            List<TrackPoint> points, java.util.function.Function<TrackPoint, @Nullable Integer> field) {
        Integer max = null;
        for (var point : points) {
            var value = field.apply(point);
            if (value != null && (max == null || value > max)) {
                max = value;
            }
        }
        return max;
    }
}
