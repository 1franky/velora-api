package mx.velora.api.activity;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Subidas y distribución de pendientes de una actividad (04-geospatial-and-analytics.md §7), a partir de las series de
 * altitud y distancia. Puro y sin estado; versión {@value #ALGORITHM_VERSION}.
 *
 * <ol>
 *   <li>Remuestrea la altitud cada {@value #STEP_METERS} m de distancia (interpolación lineal; los huecos de más de
 *       {@value #MAX_GAP_METERS} m, como pausas con el GPS apagado, cortan la serie en tramos).
 *   <li>Suaviza con una media móvil centrada de {@value #SMOOTH_METERS} m: el ruido barométrico o del GPS no inventa
 *       subidas.
 *   <li>Una subida empieza en un mínimo y termina en el máximo previo a un descenso mayor que la tolerancia (10 % de lo
 *       subido, entre 10 y 30 m), así un rellano corto no la parte en dos. Cuenta si sube al menos
 *       {@value #MIN_GAIN_METERS} m en al menos {@value #MIN_LENGTH_METERS} m con pendiente media de al menos
 *       {@value #MIN_GRADE_PERCENT} %.
 * </ol>
 */
public final class Climbs {

    public static final String ALGORITHM_VERSION = "climbs/1";
    static final double STEP_METERS = 20;
    static final double SMOOTH_METERS = 100;
    static final double MAX_GAP_METERS = 200;
    static final double MIN_GAIN_METERS = 25;
    static final double MIN_LENGTH_METERS = 300;
    static final double MIN_GRADE_PERCENT = 3;
    /** Límites de los rangos de pendiente, en %: bajada fuerte, bajada, bajada suave, llano, subida suave, etc. */
    public static final double[] GRADE_EDGES = {-8, -4, -1, 1, 4, 8};

    public record Climb(
            double startDistanceMeters,
            double endDistanceMeters,
            double lengthMeters,
            double startAltitudeMeters,
            double endAltitudeMeters,
            double elevationGainMeters,
            double averageGradePercent,
            double maxGradePercent) {}

    /** Metros recorridos en cada rango de pendiente; {@code from}/{@code to} nulos en los extremos abiertos. */
    public record GradeBin(
            @Nullable Double fromPercent, @Nullable Double toPercent, double distanceMeters) {}

    public record Result(List<Climb> climbs, List<GradeBin> grades) {}

    private Climbs() {}

    /** Distancia acumulada desde latitud y longitud, sin contar saltos de más de 100 m (pausas). */
    public static @Nullable Double[] distanceFromPositions(@Nullable Double[] lat, @Nullable Double[] lon) {
        var n = Math.min(lat.length, lon.length);
        var result = new Double[n];
        var total = 0.0;
        var previous = -1;
        for (int i = 0; i < n; i++) {
            if (lat[i] == null || lon[i] == null) {
                continue;
            }
            if (previous >= 0) {
                var step = TrackPrivacy.haversine(lat[previous], lon[previous], lat[i], lon[i]);
                if (step <= TrackPrivacy.MAX_STEP_METERS) total += step;
            }
            result[i] = total;
            previous = i;
        }
        return result;
    }

    public static Result of(@Nullable Double[] distance, @Nullable Double[] altitude) {
        var climbs = new ArrayList<Climb>();
        var bins = new double[GRADE_EDGES.length + 1];
        for (var segment : resample(distance, altitude)) {
            var d = segment[0];
            var h = smooth(segment[1]);
            detect(d, h, climbs);
            // Pendiente de cada tramo de 20 m medida en la ventana centrada de 100 m: más estable que tramo a tramo.
            var half = (int) Math.round(SMOOTH_METERS / STEP_METERS / 2);
            for (int i = 1; i < d.length; i++) {
                var from = Math.max(0, i - 1 - half);
                var to = Math.min(d.length - 1, i + half);
                var grade = 100 * (h[to] - h[from]) / (d[to] - d[from]);
                bins[bin(grade)] += d[i] - d[i - 1];
            }
        }
        var grades = new ArrayList<GradeBin>();
        for (int b = 0; b < bins.length; b++) {
            grades.add(new GradeBin(
                    b == 0 ? null : GRADE_EDGES[b - 1],
                    b == GRADE_EDGES.length ? null : GRADE_EDGES[b],
                    Math.round(bins[b] * 10) / 10.0));
        }
        return new Result(climbs, grades);
    }

    private static int bin(double grade) {
        var b = 0;
        while (b < GRADE_EDGES.length && grade >= GRADE_EDGES[b]) {
            b++;
        }
        return b;
    }

    /** Tramos continuos remuestreados: cada uno es {distancias, altitudes}. */
    private static List<double[][]> resample(@Nullable Double[] distance, @Nullable Double[] altitude) {
        var segments = new ArrayList<double[][]>();
        var n = Math.min(distance.length, altitude.length);
        var ds = new ArrayList<Double>();
        var hs = new ArrayList<Double>();
        double lastD = Double.NaN;
        double lastH = Double.NaN;
        for (int i = 0; i < n; i++) {
            if (distance[i] == null || altitude[i] == null) {
                continue;
            }
            double d = distance[i];
            double h = altitude[i];
            if (!Double.isNaN(lastD) && (d - lastD > MAX_GAP_METERS || d < lastD)) {
                flush(ds, hs, segments);
                lastD = Double.NaN;
            }
            if (Double.isNaN(lastD)) {
                ds.add(d);
                hs.add(h);
            } else {
                // Puntos intermedios cada STEP_METERS entre la última muestra y esta.
                var next = ds.getLast() + STEP_METERS;
                while (next <= d) {
                    var t = d == lastD ? 0 : (next - lastD) / (d - lastD);
                    ds.add(next);
                    hs.add(lastH + t * (h - lastH));
                    next += STEP_METERS;
                }
            }
            lastD = d;
            lastH = h;
        }
        flush(ds, hs, segments);
        return segments;
    }

    private static void flush(List<Double> ds, List<Double> hs, List<double[][]> segments) {
        if (ds.size() >= 2) {
            segments.add(new double[][] {
                ds.stream().mapToDouble(Double::doubleValue).toArray(),
                hs.stream().mapToDouble(Double::doubleValue).toArray()
            });
        }
        ds.clear();
        hs.clear();
    }

    private static double[] smooth(double[] h) {
        var half = (int) Math.round(SMOOTH_METERS / STEP_METERS / 2);
        var result = new double[h.length];
        for (int i = 0; i < h.length; i++) {
            var from = Math.max(0, i - half);
            var to = Math.min(h.length - 1, i + half);
            var sum = 0.0;
            for (int j = from; j <= to; j++) {
                sum += h[j];
            }
            result[i] = sum / (to - from + 1);
        }
        return result;
    }

    private static void detect(double[] d, double[] h, List<Climb> climbs) {
        var low = 0;
        var peak = 0;
        for (int i = 1; i < h.length; i++) {
            if (h[i] > h[peak]) {
                peak = i;
            }
            var gain = h[peak] - h[low];
            var tolerance = Math.min(30, Math.max(10, 0.1 * gain));
            if (peak > low && h[peak] - h[i] > tolerance) {
                emit(d, h, low, peak, climbs);
                low = i;
                peak = i;
            } else if (h[i] < h[low]) {
                low = i;
                peak = i;
            }
        }
        emit(d, h, low, peak, climbs);
    }

    private static void emit(double[] d, double[] h, int low, int peak, List<Climb> climbs) {
        if (peak <= low) {
            return;
        }
        // Sin los tramos llanos de los extremos: no diluyen la pendiente media.
        var start = low;
        for (int i = low; i <= peak; i++) {
            if (h[i] <= h[low] + 2) start = i;
        }
        var end = peak;
        for (int i = peak; i >= start; i--) {
            if (h[i] >= h[peak] - 2) end = i;
        }
        var gain = h[end] - h[start];
        var length = d[end] - d[start];
        if (gain < MIN_GAIN_METERS || length < MIN_LENGTH_METERS || 100 * gain / length < MIN_GRADE_PERCENT) {
            return;
        }
        // Pendiente máxima sostenida: en ventanas de 100 m dentro de la subida.
        var window = (int) Math.round(SMOOTH_METERS / STEP_METERS);
        var max = 100 * gain / length;
        for (int i = start; i + window <= end; i++) {
            max = Math.max(max, 100 * (h[i + window] - h[i]) / (d[i + window] - d[i]));
        }
        climbs.add(new Climb(
                round(d[start]),
                round(d[end]),
                round(length),
                round(h[start]),
                round(h[end]),
                round(gain),
                Math.round(1000 * gain / length) / 10.0,
                Math.round(max * 10) / 10.0));
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
