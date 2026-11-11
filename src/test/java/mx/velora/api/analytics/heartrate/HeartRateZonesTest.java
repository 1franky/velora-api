package mx.velora.api.analytics.heartrate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HeartRateZonesTest {

    @Test
    void zoneLimitsArePercentagesOfTheMaximum() {
        assertThat(HeartRateZones.lowerBpm(190)).containsExactly(95, 114, 133, 152, 171);
    }

    @Test
    void eachSampleCountsUntilTheNextOneAndPausesAreCapped() {
        // 190 ppm de máxima (Z1 95–113, Z2 114–132, Z3 133–151, Z4 152–170, Z5 171+): 100 → Z1, 140 y 150 → Z3,
        // 180 → Z5, 80 → debajo.
        Double[] time = {0.0, 60.0, 61.0, 62.0, 662.0, 663.0};
        Double[] hr = {100.0, 140.0, 180.0, 80.0, 150.0, null};
        var seconds = new double[6];
        assertThat(HeartRateZones.accumulate(time, hr, 190, seconds)).isTrue();
        assertThat(seconds[1]).isEqualTo(10); // 60 s hasta la siguiente, recortado a 10 (pausa)
        assertThat(seconds[3]).isEqualTo(2);
        assertThat(seconds[5]).isEqualTo(1);
        assertThat(seconds[0]).isEqualTo(10); // 600 s de pausa recortados a 10
        assertThat(seconds[2]).isZero();
        assertThat(seconds[4]).isZero();
    }

    @Test
    void withoutHeartRateNothingCounts() {
        var seconds = new double[6];
        assertThat(HeartRateZones.accumulate(new Double[] {0.0, 1.0}, new Double[] {null, null}, 190, seconds))
                .isFalse();
    }
}
