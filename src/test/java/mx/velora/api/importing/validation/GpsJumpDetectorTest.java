package mx.velora.api.importing.validation;

import static mx.velora.api.importing.validation.Activities.METER_IN_DEGREES;
import static mx.velora.api.importing.validation.Activities.moving;
import static mx.velora.api.importing.validation.Activities.point;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import mx.velora.api.importing.TrackPoint;
import org.junit.jupiter.api.Test;

class GpsJumpDetectorTest {

    @Test
    void acceptsFastButPossibleDescent() {
        var points = new ArrayList<TrackPoint>();
        for (int t = 0; t < 10; t++) {
            points.add(moving(0, t, 25)); // 90 km/h
        }

        assertThat(GpsJumpDetector.detect(points).isEmpty()).isTrue();
    }

    @Test
    void flagsIsolatedSpike() {
        var points = new ArrayList<TrackPoint>();
        for (int t = 0; t < 10; t++) {
            points.add(moving(0, t, 5));
        }
        var normal = points.get(4);
        points.set(4, point(0, 4, normal.latitude() + 2_000 * METER_IN_DEGREES, -99.0, null, null));

        assertThat(GpsJumpDetector.detect(points).stream().boxed().toList()).containsExactly(4);
    }

    @Test
    void flagsBadFirstFixAndAcceptsTheRestOfTheRide() {
        var points = new ArrayList<TrackPoint>();
        points.add(point(0, 0, 20.0, -99.0, null, null)); // ~110 km al norte
        for (int t = 1; t <= 8; t++) {
            points.add(moving(0, t, 5));
        }

        assertThat(GpsJumpDetector.detect(points).stream().boxed().toList()).containsExactly(0);
    }

    @Test
    void acceptsRelocationConfirmedByEnoughConsistentPoints() {
        var points = new ArrayList<TrackPoint>();
        for (int t = 0; t < 3; t++) {
            points.add(moving(0, t, 5));
        }
        // Tras un túnel el GPS reaparece 1 km más lejos un segundo después, y continúa de forma coherente.
        for (int t = 3; t < 3 + GpsJumpDetector.CLUSTER_SIZE + 2; t++) {
            points.add(point(0, t, 19.0 + (1_000 + t * 5) * METER_IN_DEGREES, -99.0, null, null));
        }

        var outliers = GpsJumpDetector.detect(points);

        assertThat(outliers.isEmpty())
                .as("los primeros puntos fueron confirmados entre sí; la reubicación se acepta")
                .isTrue();
    }

    @Test
    void doesNotCompareAcrossSegments() {
        var points = List.of(
                moving(0, 0, 5),
                moving(0, 1, 5),
                point(1, 2, 19.5, -99.0, null, null),
                point(1, 3, 19.5 + 5 * METER_IN_DEGREES, -99.0, null, null));

        assertThat(GpsJumpDetector.detect(points).isEmpty()).isTrue();
    }

    @Test
    void ignoresPointsWithoutTimeOrPosition() {
        var points = List.of(
                moving(0, 0, 5),
                point(0, null, 25.0, -99.0, null, null),
                point(0, 1, null, null, null, null),
                moving(0, 2, 5));

        assertThat(GpsJumpDetector.detect(points).isEmpty()).isTrue();
    }

    @Test
    void flagsTrailingJumpsThatNeverFormACluster() {
        var points = new ArrayList<TrackPoint>();
        for (int t = 0; t < 5; t++) {
            points.add(moving(0, t, 5));
        }
        points.add(point(0, 5, 19.2, -99.0, null, null));
        points.add(point(0, 6, 19.2 + 5 * METER_IN_DEGREES, -99.0, null, null));

        assertThat(GpsJumpDetector.detect(points).stream().boxed().toList()).containsExactly(5, 6);
    }
}
