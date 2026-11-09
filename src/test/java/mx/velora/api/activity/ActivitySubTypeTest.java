package mx.velora.api.activity;

import static mx.velora.api.activity.ActivitySubType.GRAVEL;
import static mx.velora.api.activity.ActivitySubType.INDOOR;
import static mx.velora.api.activity.ActivitySubType.MOUNTAIN_BIKE;
import static mx.velora.api.activity.ActivitySubType.OTHER;
import static mx.velora.api.activity.ActivitySubType.ROAD;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActivitySubTypeTest {

    @Test
    void withoutPositionsIsAlwaysIndoor() {
        assertThat(ActivitySubType.infer(false, "ROAD", "MTB")).isEqualTo(INDOOR);
    }

    @Test
    void deviceSubSportWinsOverBikeType() {
        assertThat(ActivitySubType.infer(true, "MOUNTAIN", "ROAD")).isEqualTo(MOUNTAIN_BIKE);
        assertThat(ActivitySubType.infer(true, "GRAVEL_CYCLING", null)).isEqualTo(GRAVEL);
        assertThat(ActivitySubType.infer(true, "INDOOR_CYCLING", null)).isEqualTo(INDOOR);
    }

    @Test
    void fallsBackToBikeTypeAndThenOther() {
        assertThat(ActivitySubType.infer(true, "GENERIC", "GRAVEL")).isEqualTo(GRAVEL);
        assertThat(ActivitySubType.infer(true, null, "ROAD")).isEqualTo(ROAD);
        assertThat(ActivitySubType.infer(true, null, "OTHER")).isEqualTo(OTHER);
        assertThat(ActivitySubType.infer(true, null, null)).isEqualTo(OTHER);
    }
}
