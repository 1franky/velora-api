package mx.velora.api.geospatial.newterritory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import mx.velora.api.geospatial.newterritory.NewTerritory.Confidence;
import mx.velora.api.geospatial.newterritory.NewTerritory.Sample;
import org.junit.jupiter.api.Test;

class NewTerritoryTest {

    private static final double STEP = 0.00009; // ~10 m hacia el norte

    /** {@code n} muestras en línea recta; {@code fresh} decide cuáles son nuevas. */
    private static List<Sample> line(int part, int from, int n, java.util.function.IntPredicate fresh) {
        var samples = new ArrayList<Sample>();
        for (int i = from; i < from + n; i++) {
            samples.add(new Sample(part, 19 + i * STEP, -99, fresh.test(i), false));
        }
        return samples;
    }

    @Test
    void allNewWhenNothingIsKnown() {
        var result = NewTerritory.of(line(1, 0, 101, i -> true), 5);
        assertThat(result.analyzedMeters()).isCloseTo(1_000, within(5.0));
        assertThat(result.newMeters()).isCloseTo(1_000, within(5.0));
        assertThat(result.newFraction()).isCloseTo(1, within(0.01));
        assertThat(result.stretches()).hasSize(1);
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void countsOnlyTheMiddleStretchAndIgnoresShortGpsBlips() {
        // 0–30 conocido, 31–60 nuevo (~290 m), 61–100 conocido salvo un punto suelto en 80 (desplazamiento del GPS).
        var result = NewTerritory.of(line(1, 0, 101, i -> (i > 30 && i <= 60) || i == 80), 5);
        assertThat(result.newMeters()).isCloseTo(290, within(5.0));
        assertThat(result.stretches()).hasSize(1);
        assertThat(result.stretches().getFirst()).hasSize(30);
    }

    @Test
    void doesNotJoinStretchesAcrossPauses() {
        var samples = new ArrayList<>(line(1, 0, 11, i -> true));
        samples.addAll(line(2, 500, 11, i -> true));
        var result = NewTerritory.of(samples, 5);
        assertThat(result.analyzedMeters()).isCloseTo(200, within(3.0));
        assertThat(result.stretches()).hasSize(2);
    }

    @Test
    void lowersTheConfidenceWithSparseGpsOrParallelRoads() {
        assertThat(NewTerritory.confidence(0, 5)).isEqualTo(Confidence.HIGH);
        assertThat(NewTerritory.confidence(0.2, 5)).isEqualTo(Confidence.MEDIUM);
        assertThat(NewTerritory.confidence(0, 25)).isEqualTo(Confidence.MEDIUM);
        assertThat(NewTerritory.confidence(0.5, 5)).isEqualTo(Confidence.LOW);
        assertThat(NewTerritory.confidence(0, 60)).isEqualTo(Confidence.LOW);

        var parallel = new ArrayList<Sample>();
        for (int i = 0; i < 101; i++) {
            parallel.add(new Sample(1, 19 + i * STEP, -99, true, true));
        }
        assertThat(NewTerritory.of(parallel, 5).confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    void writesGeoJson() {
        assertThat(NewTerritory.geometryJson(List.of())).isNull();
        var json = NewTerritory.geometryJson(List.of(List.of(new double[] {-99, 19}, new double[] {-99, 19.001})));
        assertThat(json)
                .isEqualTo(
                        "{\"type\":\"MultiLineString\",\"coordinates\":[[[-99.000000,19.000000],[-99.000000,19.001000]]]}");
    }
}
