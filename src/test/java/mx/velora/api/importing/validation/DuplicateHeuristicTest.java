package mx.velora.api.importing.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import mx.velora.api.importing.ActivityFootprint;
import mx.velora.api.importing.BoundingBox;
import mx.velora.api.importing.Measurement;
import mx.velora.api.importing.MetricProvenance;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class DuplicateHeuristicTest {

    private static final Instant START = Instant.parse("2026-11-08T13:15:00Z");
    private static final BoundingBox AREA = new BoundingBox(19.20, -99.20, 19.30, -99.10);

    private static ActivityFootprint footprint(
            Instant start, double elapsed, @Nullable Double distance, @Nullable BoundingBox bbox) {
        var measurement = distance == null ? null : new Measurement(distance, MetricProvenance.DEVICE_REPORTED);
        return new ActivityFootprint(start, elapsed, measurement, bbox, "test");
    }

    private final ActivityFootprint original = footprint(START, 7_200, 42_350.0, AREA);

    @Test
    void sameRideExportedTwiceIsLikelyDuplicate() {
        // Mismo recorrido exportado desde otra app: inicio desfasado, duración y distancia ligeramente distintas.
        var other = new ActivityFootprint(
                START.plusSeconds(45),
                7_150,
                new Measurement(41_500, MetricProvenance.DERIVED),
                new BoundingBox(19.21, -99.19, 19.29, -99.11),
                "test");

        assertThat(DuplicateHeuristic.isLikelyDuplicate(original, other)).isTrue();
        assertThat(DuplicateHeuristic.isLikelyDuplicate(other, original))
                .as("simétrica")
                .isTrue();
    }

    @Test
    void startMoreThanTwoMinutesApartIsNotDuplicate() {
        var other = footprint(START.plusSeconds(121), 7_200, 42_350.0, AREA);

        assertThat(DuplicateHeuristic.isLikelyDuplicate(original, other)).isFalse();
    }

    @Test
    void durationOutsideToleranceIsNotDuplicate() {
        var other = footprint(START, 7_200 * 0.94, 42_350.0, AREA);

        assertThat(DuplicateHeuristic.isLikelyDuplicate(original, other)).isFalse();
    }

    @Test
    void distanceOutsideToleranceIsNotDuplicate() {
        var other = footprint(START, 7_200, 42_350.0 * 0.96, AREA);

        assertThat(DuplicateHeuristic.isLikelyDuplicate(original, other)).isFalse();
    }

    @Test
    void disjointAreasAreNotDuplicate() {
        var other = footprint(START, 7_200, 42_350.0, new BoundingBox(20.0, -100.0, 20.1, -99.9));

        assertThat(DuplicateHeuristic.isLikelyDuplicate(original, other)).isFalse();
    }

    @Test
    void missingDistanceOrAreaIsNotUsedAsEvidence() {
        var indoor = footprint(START.plusSeconds(10), 7_180, null, null);

        assertThat(DuplicateHeuristic.isLikelyDuplicate(original, indoor)).isTrue();
    }
}
