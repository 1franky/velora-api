package mx.velora.api.activity;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Privacidad de inicio y fin del recorrido (ADR-016). Se oculta una distancia medida sobre el propio camino, con
 * una variación estable de ±30 % distinta en cada extremo y en cada actividad: con un radio fijo, los cortes de
 * todas las rodadas quedarían sobre la misma circunferencia y bastaría con ellos para ubicar el punto de salida.
 */
public final class TrackPrivacy {

    /** Distancias a ocultar en esta actividad. Nunca se exponen: delatarían cuánto falta para llegar al origen. */
    public record Trim(double startMeters, double endMeters) {}

    /** Saltos mayores se tratan como pausas (no suman distancia), así nunca se oculta menos que en el recorrido. */
    static final double MAX_STEP_METERS = 100;

    private TrackPrivacy() {}

    public static Optional<Trim> forActivity(UUID activityId, int meters) {
        if (meters <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Trim(
                meters * factor(activityId.getMostSignificantBits()),
                meters * factor(activityId.getLeastSignificantBits())));
    }

    /** Número estable en [0.7, 1.3) a partir de bits aleatorios del UUID. */
    private static double factor(long bits) {
        var unit = (bits >>> 11) * 0x1.0p-53;
        return 0.7 + 0.6 * unit;
    }

    /**
     * Pone en {@code null} las posiciones dentro de los tramos ocultos. La distancia se acumula sobre el camino con
     * haversine, sin contar saltos mayores a {@link #MAX_STEP_METERS}.
     */
    public static void maskPositions(@Nullable Double[] lat, @Nullable Double[] lon, Trim trim) {
        var n = Math.min(lat.length, lon.length);
        var cumulative = new double[n];
        var total = 0.0;
        var previous = -1;
        for (int i = 0; i < n; i++) {
            if (lat[i] == null || lon[i] == null) {
                cumulative[i] = total;
                continue;
            }
            if (previous >= 0) {
                var step = haversine(lat[previous], lon[previous], lat[i], lon[i]);
                if (step <= MAX_STEP_METERS) total += step;
            }
            cumulative[i] = total;
            previous = i;
        }
        for (int i = 0; i < n; i++) {
            if (cumulative[i] < trim.startMeters() || cumulative[i] > total - trim.endMeters()) {
                lat[i] = null;
                lon[i] = null;
            }
        }
    }

    static double haversine(double lat1, double lon1, double lat2, double lon2) {
        var rad = Math.PI / 180;
        var dLat = (lat2 - lat1) * rad;
        var dLon = (lon2 - lon1) * rad;
        var h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * 6_371_008.8 * Math.asin(Math.sqrt(h));
    }
}
