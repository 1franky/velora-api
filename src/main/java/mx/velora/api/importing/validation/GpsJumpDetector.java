package mx.velora.api.importing.validation;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import mx.velora.api.importing.TrackPoint;

/**
 * Detecta posiciones atípicas por velocidad implícita imposible para una bicicleta.
 *
 * <p>Cada punto se compara con el último punto aceptado (ancla) del mismo segmento. Si la velocidad implícita
 * supera {@link #MAX_SPEED_MPS}, el punto queda pendiente. Los pendientes:
 *
 * <ul>
 *   <li>se marcan como atípicos si después aparece un punto coherente con el ancla (fue un pico aislado);
 *   <li>se aceptan como nueva ubicación si se acumulan {@link #CLUSTER_SIZE} pendientes coherentes entre sí (el GPS
 *       se reubicó). Si el ancla nunca fue confirmada por un vecino coherente, era la mala (p. ej. el primer fix) y
 *       se marca como atípica.
 * </ul>
 *
 * <p>Los segmentos se evalúan por separado: un salto entre segmentos corresponde a una pausa. Los puntos sin
 * posición o sin tiempo no participan.
 */
final class GpsJumpDetector {

    static final String VERSION = "gps-jumps/1";

    /** 30 m/s = 108 km/h: por encima de cualquier velocidad sostenida en bicicleta, incluso en descenso. */
    static final double MAX_SPEED_MPS = 30.0;

    static final int CLUSTER_SIZE = 5;

    private GpsJumpDetector() {}

    /** Devuelve los índices de {@code points} cuya posición es atípica. */
    static BitSet detect(List<TrackPoint> points) {
        var outliers = new BitSet();
        var pending = new ArrayList<Integer>();
        int anchor = -1;
        boolean anchorConfirmed = false;
        int segment = Integer.MIN_VALUE;

        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i);
            if (!point.hasPosition() || point.time() == null) {
                continue;
            }
            if (anchor < 0 || point.segment() != segment) {
                markAll(pending, outliers);
                anchor = i;
                anchorConfirmed = false;
                segment = point.segment();
                continue;
            }
            if (Geo.impliedSpeedMps(points.get(anchor), point) <= MAX_SPEED_MPS) {
                markAll(pending, outliers);
                anchor = i;
                anchorConfirmed = true;
                continue;
            }
            if (!pending.isEmpty() && Geo.impliedSpeedMps(points.get(pending.getLast()), point) > MAX_SPEED_MPS) {
                markAll(pending, outliers);
            }
            pending.add(i);
            if (pending.size() >= CLUSTER_SIZE) {
                if (!anchorConfirmed) {
                    outliers.set(anchor);
                }
                anchor = pending.getLast();
                anchorConfirmed = true;
                pending.clear();
            }
        }
        markAll(pending, outliers);
        return outliers;
    }

    private static void markAll(List<Integer> pending, BitSet outliers) {
        pending.forEach(outliers::set);
        pending.clear();
    }
}
