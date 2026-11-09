package mx.velora.api.importing.validation;

import static mx.velora.api.importing.validation.Activities.METER_IN_DEGREES;
import static mx.velora.api.importing.validation.Activities.START;
import static mx.velora.api.importing.validation.Activities.point;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import mx.velora.api.importing.ActivityFootprint;
import mx.velora.api.importing.MetricProvenance;
import org.junit.jupiter.api.Test;

class FootprintCalculatorTest {

    private static final ZoneId MEXICO_CITY = ZoneId.of("America/Mexico_City");

    @Test
    void prefersDeviceReportedDistance() {
        var footprint = FootprintCalculator.compute(Activities.of(List.of(
                point(0, 0, 19.0, -99.0, 0.0, null),
                point(0, 60, 19.0 + 100 * METER_IN_DEGREES, -99.0, 250.0, null),
                point(0, 120, 19.0 + 200 * METER_IN_DEGREES, -99.0, 240.0, null))));

        assertThat(footprint.distance().provenance()).isEqualTo(MetricProvenance.DEVICE_REPORTED);
        assertThat(footprint.distance().value()).as("mayor distancia acumulada").isEqualTo(250.0);
    }

    @Test
    void derivesDistanceWithinSegmentsWhenDeviceDoesNotReportIt() {
        var footprint = FootprintCalculator.compute(Activities.of(List.of(
                point(0, 0, 19.0, -99.0, null, null),
                point(0, 60, 19.0 + 100 * METER_IN_DEGREES, -99.0, null, null),
                // Pausa: el desplazamiento entre segmentos no cuenta.
                point(1, 600, 19.0 + 1_100 * METER_IN_DEGREES, -99.0, null, null),
                point(1, 660, 19.0 + 1_150 * METER_IN_DEGREES, -99.0, null, null))));

        assertThat(footprint.distance().provenance()).isEqualTo(MetricProvenance.DERIVED);
        assertThat(footprint.distance().value()).isCloseTo(150.0, within(0.5));
        assertThat(footprint.algorithmVersion()).isEqualTo(FootprintCalculator.VERSION);
    }

    @Test
    void computesElapsedTimeIncludingPausesAndBoundingBox() {
        var footprint = FootprintCalculator.compute(Activities.of(List.of(
                point(0, 0, 19.0, -99.2, null, null),
                point(0, 30, 19.3, -99.1, null, null),
                point(1, 3_600, 19.1, -99.3, null, null))));

        assertThat(footprint.startedAt()).isEqualTo(START);
        assertThat(footprint.elapsedSeconds()).isEqualTo(3_600.0);
        assertThat(footprint.boundingBox().minLatitude()).isEqualTo(19.0);
        assertThat(footprint.boundingBox().maxLatitude()).isEqualTo(19.3);
        assertThat(footprint.boundingBox().minLongitude()).isEqualTo(-99.3);
        assertThat(footprint.boundingBox().maxLongitude()).isEqualTo(-99.1);
    }

    @Test
    void indoorActivityHasNoBoundingBoxNorDerivedDistance() {
        var footprint = FootprintCalculator.compute(
                Activities.of(List.of(point(0, 0, null, null, null, null), point(0, 60, null, null, null, null))));

        assertThat(footprint.boundingBox()).isNull();
        assertThat(footprint.distance()).isNull();
    }

    @Test
    void localStartDateHandlesMidnightCrossingInMexicoCity() {
        // 23:55 del 08/11 en Ciudad de México (UTC-6) = 05:55Z del 09/11; termina después de medianoche local.
        var startUtc = java.time.Instant.parse("2026-11-09T05:55:00Z");
        var footprint = new ActivityFootprint(startUtc, 1_200, null, null, FootprintCalculator.VERSION);

        assertThat(footprint.localStartDate(MEXICO_CITY)).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(footprint.localStartDate(ZoneId.of("UTC"))).isEqualTo(LocalDate.of(2026, 11, 9));
    }
}
