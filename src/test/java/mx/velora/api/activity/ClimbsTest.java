package mx.velora.api.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

class ClimbsTest {

    /** Muestras cada 5 m durante {@code meters}, con la altitud dada por la función de la distancia. */
    private static Double[][] profile(double meters, DoubleUnaryOperator altitude) {
        var n = (int) (meters / 5) + 1;
        var d = new Double[n];
        var h = new Double[n];
        for (int i = 0; i < n; i++) {
            d[i] = i * 5.0;
            h[i] = altitude.applyAsDouble(d[i]);
        }
        return new Double[][] {d, h};
    }

    @Test
    void findsOneSteadyClimbBetweenFlats() {
        // 1 km llano, 2 km al 6 % (120 m), 1 km llano.
        var p = profile(4_000, x -> x < 1_000 ? 100 : x < 3_000 ? 100 + 0.06 * (x - 1_000) : 220);
        var result = Climbs.of(p[0], p[1]);
        assertThat(result.climbs()).hasSize(1);
        var climb = result.climbs().getFirst();
        assertThat(climb.elevationGainMeters()).isCloseTo(120, within(4.0));
        assertThat(climb.averageGradePercent()).isCloseTo(6, within(0.4));
        assertThat(climb.startDistanceMeters()).isCloseTo(1_000, within(80.0));
        assertThat(climb.endDistanceMeters()).isCloseTo(3_000, within(80.0));
        assertThat(climb.lengthMeters())
                .isCloseTo(climb.endDistanceMeters() - climb.startDistanceMeters(), within(0.2));
    }

    @Test
    void aShortDipDoesNotSplitAClimbButARealDescentDoes() {
        // Sube 60 m, baja 5 m, sube 60 m: una subida. Luego baja 80 m y sube 40 m: otra.
        var p = profile(7_000, x -> {
            if (x < 1_000) return 60 * x / 1_000;
            if (x < 1_200) return 60 - 5 * (x - 1_000) / 200;
            if (x < 2_200) return 55 + 65 * (x - 1_200) / 1_000;
            if (x < 3_500) return 120 - 80 * (x - 2_200) / 1_300;
            if (x < 4_500) return 40 + 40 * (x - 3_500) / 1_000;
            return 80;
        });
        var climbs = Climbs.of(p[0], p[1]).climbs();
        assertThat(climbs).hasSize(2);
        // El suavizado y el recorte de los extremos llanos (±2 m) restan unos metros a lo subido.
        assertThat(climbs.get(0).elevationGainMeters()).isCloseTo(120, within(10.0));
        assertThat(climbs.get(1).elevationGainMeters()).isCloseTo(40, within(6.0));
    }

    @Test
    void noiseOnAFlatRoadIsNotAClimb() {
        var random = new Random(3);
        // Ruido blanco de 1 m por muestra (altímetro barométrico típico).
        var p = profile(10_000, x -> 2_240 + random.nextGaussian());
        var result = Climbs.of(p[0], p[1]);
        assertThat(result.climbs()).isEmpty();
        var flat = result.grades().get(3);
        assertThat(flat.fromPercent()).isEqualTo(-1.0);
        assertThat(flat.toPercent()).isEqualTo(1.0);
        assertThat(flat.distanceMeters()).isGreaterThan(8_000);
    }

    @Test
    void ignoresGentleOrShortRises() {
        // 2 % durante 3 km (60 m) no llega al 3 %; 40 m al 10 % no llega a 300 m.
        assertThat(Climbs.of(profile(3_000, x -> 0.02 * x)[0], profile(3_000, x -> 0.02 * x)[1])
                        .climbs())
                .isEmpty();
        var steepShort = profile(1_000, x -> x < 400 ? 0 : x < 600 ? 0.12 * (x - 400) : 24);
        assertThat(Climbs.of(steepShort[0], steepShort[1]).climbs()).isEmpty();
    }

    @Test
    void gradeBinsAddUpToTheDistance() {
        var p = profile(4_000, x -> 50 * Math.sin(x / 400));
        var total = Climbs.of(p[0], p[1]).grades().stream()
                .mapToDouble(Climbs.GradeBin::distanceMeters)
                .sum();
        assertThat(total).isCloseTo(4_000, within(25.0));
    }

    @Test
    void gapsSplitTheProfileAndMissingValuesAreSkipped() {
        var p = profile(2_000, x -> 0.06 * x);
        p[1][100] = null;
        for (int i = 200; i < p[0].length; i++) {
            p[0][i] = p[0][i] + 1_000; // salto de 1 km: tramo nuevo
        }
        var result = Climbs.of(p[0], p[1]);
        var total = result.grades().stream()
                .mapToDouble(Climbs.GradeBin::distanceMeters)
                .sum();
        assertThat(total).isLessThan(2_100);
    }

    @Test
    void distanceFromPositionsSkipsJumps() {
        Double[] lat = {19.0, 19.0001, null, 19.0002, 19.5};
        Double[] lon = {-99.0, -99.0, null, -99.0, -99.0};
        var distance = Climbs.distanceFromPositions(lat, lon);
        assertThat(distance[2]).isNull();
        assertThat(distance[3]).isCloseTo(22.2, within(0.5));
        assertThat(distance[4]).isEqualTo(distance[3]);
    }
}
