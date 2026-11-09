package mx.velora.api.importing.validation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import mx.velora.api.importing.TrackPoint;

/**
 * Detecta altitudes atípicas. Entre dos altitudes consecutivas, un cambio de más de {@link #MIN_JUMP_METERS} con
 * velocidad vertical mayor que {@link #MAX_VERTICAL_SPEED_MPS} es imposible en bicicleta: uno de los dos lados es
 * erróneo. Tras una pausa larga el cambio no cuenta como salto, porque su velocidad vertical es baja.
 *
 * <p>Algoritmo ({@value #VERSION}): la serie se divide en tramos separados por saltos; el tramo más largo es la
 * referencia; los demás se aceptan, hacia adelante y hacia atrás, solo si empalman sin salto con el último punto
 * aceptado. Lo no aceptado es atípico.
 *
 * <p>Caso real que lo motiva: exportaciones GPX cuyos primeros 4–7 puntos tienen altitud ≈0 m antes de que el
 * altímetro dé lectura, seguidos de la altitud real. Sumaban toda la altitud del lugar como desnivel falso. Un detector que
 * confía en el primer punto los acepta porque son coherentes entre sí.
 */
final class AltitudeJumpDetector {

    static final String VERSION = "altitude-jumps/1";

    static final double MIN_JUMP_METERS = 20;

    /** 5 m/s de velocidad vertical: muy por encima de cualquier ascenso o descenso en bicicleta. */
    static final double MAX_VERTICAL_SPEED_MPS = 5;

    private AltitudeJumpDetector() {}

    static BitSet detect(List<TrackPoint> points) {
        var runs = runs(points);
        var outliers = new BitSet();
        if (runs.size() <= 1) {
            return outliers;
        }
        int reference = 0;
        for (int r = 1; r < runs.size(); r++) {
            if (runs.get(r).size() > runs.get(reference).size()) {
                reference = r;
            }
        }
        var last = runs.get(reference).getLast();
        for (int r = reference + 1; r < runs.size(); r++) {
            var run = runs.get(r);
            if (isJump(points.get(last), points.get(run.getFirst()))) {
                run.forEach(outliers::set);
            } else {
                last = run.getLast();
            }
        }
        var first = runs.get(reference).getFirst();
        for (int r = reference - 1; r >= 0; r--) {
            var run = runs.get(r);
            if (isJump(points.get(run.getLast()), points.get(first))) {
                run.forEach(outliers::set);
            } else {
                first = run.getFirst();
            }
        }
        return outliers;
    }

    /** Índices de puntos con altitud y tiempo, agrupados en tramos sin saltos entre vecinos. */
    private static List<List<Integer>> runs(List<TrackPoint> points) {
        var runs = new ArrayList<List<Integer>>();
        List<Integer> current = null;
        int previous = -1;
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i);
            if (point.altitudeMeters() == null || point.time() == null) {
                continue;
            }
            if (current == null || isJump(points.get(previous), point)) {
                current = new ArrayList<>();
                runs.add(current);
            }
            current.add(i);
            previous = i;
        }
        return runs;
    }

    private static boolean isJump(TrackPoint from, TrackPoint to) {
        var meters = Math.abs(to.altitudeMeters() - from.altitudeMeters());
        var seconds = Math.abs(Duration.between(from.time(), to.time()).toMillis()) / 1000.0;
        return meters > MIN_JUMP_METERS && meters / Math.max(seconds, 1.0) > MAX_VERTICAL_SPEED_MPS;
    }
}
