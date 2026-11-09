package mx.velora.api.importing.parsing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.RejectionReason;
import mx.velora.api.importing.TrackPoint;
import org.jspecify.annotations.Nullable;

/** Acumula muestras aplicando el límite de {@link ParserLimits}. */
final class PointCollector {

    private final ParserLimits limits;
    private final List<TrackPoint> points = new ArrayList<>();

    PointCollector(ParserLimits limits) {
        this.limits = limits;
    }

    void add(TrackPoint point) {
        if (points.size() >= limits.maxPoints()) {
            throw new ImportRejectedException(
                    RejectionReason.TOO_MANY_POINTS, "el archivo supera " + limits.maxPoints() + " muestras");
        }
        points.add(point);
    }

    List<TrackPoint> points() {
        return List.copyOf(points);
    }

    @Nullable Instant firstTime() {
        for (var point : points) {
            if (point.time() != null) {
                return point.time();
            }
        }
        return null;
    }
}
