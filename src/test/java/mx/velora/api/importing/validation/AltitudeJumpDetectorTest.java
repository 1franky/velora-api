package mx.velora.api.importing.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mx.velora.api.importing.TrackPoint;
import org.junit.jupiter.api.Test;

class AltitudeJumpDetectorTest {

    private static final Instant START = Instant.parse("2026-11-08T13:00:00Z");

    private static TrackPoint at(int second, double altitude) {
        return new TrackPoint(0, START.plusSeconds(second), null, null, altitude, null, null, null, null, null, null);
    }

    private static List<Integer> outliers(List<TrackPoint> points) {
        return AltitudeJumpDetector.detect(points).stream().boxed().toList();
    }

    /** Subida suave desde 1 500 m, una muestra por segundo desde {@code from}. */
    private static List<TrackPoint> climb(int from, int count) {
        var points = new ArrayList<TrackPoint>();
        for (int i = 0; i < count; i++) {
            points.add(at(from + i, 1_500 + i * 0.2));
        }
        return points;
    }

    @Test
    void smoothClimbHasNoOutliers() {
        assertThat(outliers(climb(0, 200))).isEmpty();
    }

    @Test
    void flagsLeadingSamplesAtZeroBeforeTheAltimeterReads() {
        // Caso real de exportaciones GPX: los primeros puntos valen ≈0 m.
        var points = new ArrayList<TrackPoint>();
        for (int i = 0; i < 6; i++) {
            points.add(at(i, 0));
        }
        points.addAll(climb(6, 100));

        assertThat(outliers(points)).containsExactly(0, 1, 2, 3, 4, 5);
    }

    @Test
    void flagsIsolatedSpikeAndTrailingGarbage() {
        var points = new ArrayList<>(climb(0, 50));
        points.set(20, at(20, 9_999));
        points.add(at(50, 0));
        points.add(at(51, 0));

        assertThat(outliers(points)).containsExactly(20, 50, 51);
    }

    @Test
    void largeChangeAfterALongPauseIsNotAJump() {
        var points = new ArrayList<>(climb(0, 50)); // termina en 1 509.8 m
        var resume = 50 + 6 * 3_600;
        for (int i = 0; i < 10; i++) {
            // 47 m más arriba tras 6 h (otro clima, otra calibración): velocidad vertical ínfima.
            points.add(at(resume + i, 1_509.8 + 47 + i * 0.2));
        }

        assertThat(outliers(points)).isEmpty();
    }
}
