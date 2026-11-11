package mx.velora.api.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrackPrivacyTest {

    @Test
    void disabledMeansNoTrim() {
        assertThat(TrackPrivacy.forActivity(UUID.randomUUID(), 0)).isEmpty();
    }

    @Test
    void eachEndVariesWithinThirtyPercentAndIsStablePerActivity() {
        for (int i = 0; i < 1_000; i++) {
            var id = UUID.randomUUID();
            var trim = TrackPrivacy.forActivity(id, 300).orElseThrow();
            assertThat(trim.startMeters()).isBetween(210.0, 390.0);
            assertThat(trim.endMeters()).isBetween(210.0, 390.0);
            assertThat(TrackPrivacy.forActivity(id, 300)).contains(trim);
        }
    }

    @Test
    void distancesDifferBetweenActivitiesSoCutsDoNotDrawACircle() {
        var distinct = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> TrackPrivacy.forActivity(UUID.randomUUID(), 300)
                        .orElseThrow()
                        .startMeters())
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(50);
    }

    /** Recta hacia el norte: ~5.56 m entre muestras (0.00005° de latitud). */
    private static Double[][] straightNorth(int n) {
        var lat = new Double[n];
        var lon = new Double[n];
        for (int i = 0; i < n; i++) {
            lat[i] = 19.0 + i * 0.00005;
            lon[i] = -99.0;
        }
        return new Double[][] {lat, lon};
    }

    @Test
    void masksPositionsAlongThePathAtBothEnds() {
        var ride = straightNorth(1_000);
        TrackPrivacy.maskPositions(ride[0], ride[1], new TrackPrivacy.Trim(300, 500));

        var firstVisible = indexOfFirstVisible(ride[0]);
        var lastVisible = indexOfLastVisible(ride[0]);
        assertThat(firstVisible * 5.56).isBetween(295.0, 310.0);
        assertThat((999 - lastVisible) * 5.56).isBetween(495.0, 510.0);
        assertThat(ride[1][firstVisible - 1]).isNull();
        assertThat(ride[1][firstVisible]).isNotNull();
    }

    @Test
    void gpsJumpsDoNotCountAsDistanceSoMoreIsHiddenNotLess() {
        var ride = straightNorth(200);
        // Un salto de ~5.5 km justo después del inicio (p. ej. GPS recuperando señal).
        for (int i = 1; i < 200; i++) ride[0][i] += 0.05;
        TrackPrivacy.maskPositions(ride[0], ride[1], new TrackPrivacy.Trim(300, 0));

        assertThat(indexOfFirstVisible(ride[0]) * 5.56).isGreaterThanOrEqualTo(295.0);
    }

    @Test
    void aRideShorterThanTheTrimIsHiddenCompletely() {
        var ride = straightNorth(50); // ~270 m
        TrackPrivacy.maskPositions(ride[0], ride[1], new TrackPrivacy.Trim(200, 200));
        assertThat(Arrays.stream(ride[0]).allMatch(v -> v == null)).isTrue();
    }

    private static int indexOfFirstVisible(Double[] values) {
        for (int i = 0; i < values.length; i++) if (values[i] != null) return i;
        return -1;
    }

    private static int indexOfLastVisible(Double[] values) {
        for (int i = values.length - 1; i >= 0; i--) if (values[i] != null) return i;
        return -1;
    }
}
