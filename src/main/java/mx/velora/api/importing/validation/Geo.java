package mx.velora.api.importing.validation;

import java.time.Duration;
import mx.velora.api.importing.TrackPoint;

/** Cálculos geodésicos simples sobre WGS84. */
final class Geo {

    /** Radio medio terrestre (IUGG), en metros. */
    static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private Geo() {}

    /** Distancia de círculo máximo (haversine) en metros. */
    static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        var phi1 = Math.toRadians(lat1);
        var phi2 = Math.toRadians(lat2);
        var dPhi = Math.toRadians(lat2 - lat1);
        var dLambda = Math.toRadians(lon2 - lon1);
        var a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** Requiere que ambos puntos tengan posición. */
    static double distanceMeters(TrackPoint a, TrackPoint b) {
        return haversineMeters(a.latitude(), a.longitude(), b.latitude(), b.longitude());
    }

    /**
     * Velocidad implícita entre dos puntos con posición y tiempo. Intervalos menores a 1 s cuentan como 1 s para no
     * dividir entre cero con muestras repetidas.
     */
    static double impliedSpeedMps(TrackPoint a, TrackPoint b) {
        var seconds = Math.abs(Duration.between(a.time(), b.time()).toMillis()) / 1000.0;
        return distanceMeters(a, b) / Math.max(seconds, 1.0);
    }
}
