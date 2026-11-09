package mx.velora.api.importing.derive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mx.velora.api.importing.DerivedMetrics;
import mx.velora.api.importing.DerivedMetrics.Parameters;
import mx.velora.api.importing.FileFormat;
import mx.velora.api.importing.ImportedActivity;
import mx.velora.api.importing.ParserInfo;
import mx.velora.api.importing.ReportedSummary;
import mx.velora.api.importing.TrackPoint;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class DerivedMetricsCalculatorTest {

    private static final Instant START = Instant.parse("2026-11-08T13:00:00Z");
    private static final double METER = 1.0 / 111_195.0;

    /** Constructor fluido de muestras sintéticas. */
    private static final class Ride {
        private final List<TrackPoint> points = new ArrayList<>();
        private int segment;
        private double northMeters;

        Ride move(int fromSecond, int toSecond, double metersPerSecond) {
            for (int t = fromSecond; t <= toSecond; t++) {
                add(t, null, null, null, null, null);
                northMeters += metersPerSecond;
            }
            return this;
        }

        Ride add(
                int second,
                @Nullable Double altitude,
                @Nullable Double distance,
                @Nullable Integer heartRate,
                @Nullable Integer cadence,
                @Nullable Integer power) {
            points.add(new TrackPoint(
                    segment,
                    START.plusSeconds(second),
                    19.0 + northMeters * METER,
                    -99.0,
                    altitude,
                    distance,
                    heartRate,
                    cadence,
                    power,
                    null,
                    null));
            return this;
        }

        Ride pause() {
            segment++;
            return this;
        }

        DerivedMetrics compute() {
            return compute(DerivedMetrics.Parameters.DEFAULT);
        }

        DerivedMetrics compute(Parameters parameters) {
            return DerivedMetricsCalculator.compute(
                    new ImportedActivity(
                            FileFormat.GPX,
                            new ParserInfo("test", "1"),
                            null,
                            null,
                            START,
                            List.of(),
                            points,
                            ReportedSummary.EMPTY),
                    parameters);
        }
    }

    @Test
    void countsOnlyIntervalsAboveTheSpeedThreshold() {
        // 60 s rodando a 5 m/s, 120 s detenido (sin pausa del cronómetro) y 60 s más rodando.
        var metrics =
                new Ride().move(0, 60, 5).move(61, 180, 0).move(181, 240, 5).compute();

        assertThat(metrics.elapsedTimeSeconds()).isEqualTo(240);
        assertThat(metrics.movingTimeSeconds()).isCloseTo(121, within(1.0));
        assertThat(metrics.algorithmVersion()).isEqualTo(DerivedMetricsCalculator.VERSION);
        assertThat(metrics.parameters()).isEqualTo(Parameters.DEFAULT);
    }

    @Test
    void ignoresIntervalsAcrossPausesAndLongGaps() {
        var ride = new Ride().move(0, 10, 5).pause().move(1_000, 1_010, 5);
        ride.add(1_400, null, null, null, null, null); // 390 s sin muestras: mayor que el máximo de 300 s.

        assertThat(ride.compute().movingTimeSeconds()).isCloseTo(20, within(0.5));
    }

    @Test
    void indoorWithoutSpeedDataCountsAsMoving() {
        var points = new ArrayList<TrackPoint>();
        for (int t = 0; t <= 60; t++) {
            points.add(new TrackPoint(0, START.plusSeconds(t), null, null, null, null, 120, 85, 200, null, null));
        }
        var metrics = DerivedMetricsCalculator.compute(
                new ImportedActivity(
                        FileFormat.FIT,
                        new ParserInfo("t", "1"),
                        null,
                        null,
                        START,
                        List.of(),
                        points,
                        ReportedSummary.EMPTY),
                Parameters.DEFAULT);

        assertThat(metrics.movingTimeSeconds()).isEqualTo(60);
        assertThat(metrics.distanceMeters()).isNull();
        assertThat(metrics.averageSpeedMps()).isNull();
        assertThat(metrics.averagePowerWatts()).isEqualTo(200);
    }

    @Test
    void derivesDistanceAndAverageMovingSpeed() {
        var metrics = new Ride().move(0, 100, 5).move(101, 200, 0).compute();

        assertThat(metrics.distanceMeters()).isCloseTo(505, within(2.0));
        assertThat(metrics.averageSpeedMps()).isCloseTo(5, within(0.1));
    }

    @Test
    void elevationUsesHysteresisToIgnoreSmallOscillations() {
        var ride = new Ride();
        double[] altitudes = {100, 100.4, 99.8, 100.3, 101.5, 103, 102.6, 103.2, 105, 104.5, 101};
        for (int i = 0; i < altitudes.length; i++) {
            ride.add(i, altitudes[i], null, null, null, null);
        }

        var metrics = ride.compute();

        // Con 1 m: sube 100 → 101.5 → 103 → 105 (5 m) y baja 105 → 101 (4 m); las oscilaciones < 1 m no cuentan.
        assertThat(metrics.ascentMeters()).isCloseTo(5, within(1e-9));
        assertThat(metrics.descentMeters()).isCloseTo(4, within(1e-9));
        assertThat(ride.compute(new Parameters(1, 300, 0, 10)).ascentMeters())
                .as("sin histéresis el ruido suma más")
                .isGreaterThan(5);
    }

    @Test
    void elevationIsNullWithoutAltitudes() {
        var metrics = new Ride().move(0, 10, 5).compute();

        assertThat(metrics.ascentMeters()).isNull();
        assertThat(metrics.descentMeters()).isNull();
    }

    @Test
    void maxSpeedWindowFiltersSingleSampleSpikes() {
        var ride = new Ride();
        double distance = 0;
        for (int t = 0; t <= 60; t++) {
            distance += t == 30 ? 40 : 10; // un pico de 40 m/s en una sola muestra
            ride.add(t, null, distance, null, null, null);
        }

        assertThat(ride.compute(new Parameters(1, 300, 1, 1)).maxSpeedMps()).isCloseTo(40, within(0.01));
        assertThat(ride.compute().maxSpeedMps()).as("ventana de 10 s").isCloseTo(13, within(0.01));
    }

    @Test
    void averagesAreTimeWeightedOverMovingIntervals() {
        var ride = new Ride();
        for (int t = 0; t <= 10; t++) {
            ride.add(t, null, t * 5.0, 140, t % 2 == 0 ? 90 : 0, t % 2 == 0 ? 300 : 0);
        }
        for (int t = 11; t <= 30; t++) {
            ride.add(t, null, 50.0, 100, 0, 0); // detenido: no cuenta para las medias
        }

        var metrics = ride.compute();

        assertThat(metrics.averageHeartRateBpm()).isEqualTo(140);
        assertThat(metrics.maxHeartRateBpm()).isEqualTo(140);
        assertThat(metrics.averageCadenceRpm()).as("sin ceros").isEqualTo(90);
        assertThat(metrics.averagePowerWatts()).as("con ceros").isEqualTo(150);
        assertThat(metrics.maxPowerWatts()).isEqualTo(300);
    }
}
