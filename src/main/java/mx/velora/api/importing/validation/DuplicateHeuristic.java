package mx.velora.api.importing.validation;

import java.time.Duration;
import mx.velora.api.importing.ActivityFootprint;

/**
 * Tercer nivel de deduplicación (ADR-009): compara huellas de actividades distintas.
 *
 * <p>Una coincidencia solo debe generar advertencia o revisión; nunca un descarte automático. Las comparaciones de
 * distancia y recuadro se omiten cuando alguna de las dos actividades no tiene ese dato (p. ej. indoor).
 */
public final class DuplicateHeuristic {

    public static final String VERSION = "dedup-heuristic/1";

    static final Duration START_TOLERANCE = Duration.ofMinutes(2);
    static final double DURATION_TOLERANCE = 0.05;
    static final double DISTANCE_TOLERANCE = 0.03;

    private DuplicateHeuristic() {}

    public static boolean isLikelyDuplicate(ActivityFootprint a, ActivityFootprint b) {
        if (Duration.between(a.startedAt(), b.startedAt()).abs().compareTo(START_TOLERANCE) > 0) {
            return false;
        }
        if (!withinRelative(a.elapsedSeconds(), b.elapsedSeconds(), DURATION_TOLERANCE)) {
            return false;
        }
        if (a.distance() != null
                && b.distance() != null
                && !withinRelative(a.distance().value(), b.distance().value(), DISTANCE_TOLERANCE)) {
            return false;
        }
        return a.boundingBox() == null
                || b.boundingBox() == null
                || a.boundingBox().intersects(b.boundingBox());
    }

    /** Diferencia relativa respecto al mayor de los dos valores. */
    private static boolean withinRelative(double x, double y, double tolerance) {
        var reference = Math.max(Math.abs(x), Math.abs(y));
        return reference == 0 || Math.abs(x - y) <= tolerance * reference;
    }
}
