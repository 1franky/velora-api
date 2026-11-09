package mx.velora.api.importing.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import mx.velora.api.importing.FileFormat;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.ImportedActivity;
import mx.velora.api.importing.RejectionReason;
import mx.velora.api.importing.TrackPoint;
import org.junit.jupiter.api.Test;

class TcxParserTest {

    private final TcxParser parser = new TcxParser(ParserLimits.DEFAULT);

    private ImportedActivity parse(String fixture) {
        return parser.parse(Fixtures.open("tcx/" + fixture));
    }

    @Test
    void readsActivityMetadataAndSourceKey() {
        var activity = parse("with-laps.tcx");

        assertThat(activity.format()).isEqualTo(FileFormat.TCX);
        assertThat(activity.parser().name()).isEqualTo("velora-tcx");
        assertThat(activity.title()).isNull();
        assertThat(activity.startedAt()).isEqualTo(Instant.parse("2026-11-08T13:15:00Z"));
        assertThat(activity.sourceActivityKey()).isEqualTo("tcx:1234567890:2026-11-08T13:15:00.000Z");
    }

    @Test
    void readsTrackpointsWithSensorsAndExtensions() {
        var points = parse("with-laps.tcx").points();

        assertThat(points).hasSize(4);
        var first = points.getFirst();
        assertThat(first.time()).isEqualTo(Instant.parse("2026-11-08T13:15:00Z"));
        assertThat(first.latitude()).isEqualTo(19.25);
        assertThat(first.longitude()).isEqualTo(-99.15);
        assertThat(first.altitudeMeters()).isEqualTo(2400.5);
        assertThat(first.distanceMeters()).isEqualTo(0.0);
        assertThat(first.heartRateBpm()).isEqualTo(120);
        assertThat(first.cadenceRpm()).isEqualTo(80);
        assertThat(first.speedMps()).isEqualTo(6.5);
        assertThat(first.powerWatts()).isEqualTo(190);
        assertThat(first.temperatureCelsius()).as("TCX no tiene temperatura").isNull();

        var second = points.get(1);
        assertThat(second.cadenceRpm()).as("sensor ausente permanece null").isNull();
        assertThat(second.powerWatts()).isNull();
        assertThat(second.distanceMeters()).isEqualTo(6.6);
    }

    @Test
    void eachTrackIsASegment() {
        var points = parse("with-laps.tcx").points();

        assertThat(points).extracting(TrackPoint::segment).containsExactly(0, 0, 1, 2);
    }

    @Test
    void readsLapMetricsWithoutMixingTrackpointValues() {
        var laps = parse("with-laps.tcx").laps();

        assertThat(laps).hasSize(2);
        var first = laps.getFirst();
        assertThat(first.lapNumber()).isEqualTo(1);
        assertThat(first.startedAt()).isEqualTo(Instant.parse("2026-11-08T13:15:00Z"));
        assertThat(first.timerTimeSeconds()).isEqualTo(600.5);
        assertThat(first.elapsedTimeSeconds())
                .as("TCX no informa tiempo transcurrido")
                .isNull();
        assertThat(first.distanceMeters())
                .as("distancia de la vuelta, no del trackpoint")
                .isEqualTo(4000.0);
        assertThat(first.maxSpeedMps()).isEqualTo(12.5);
        assertThat(first.averageSpeedMps()).isEqualTo(6.66);
        assertThat(first.averageHeartRateBpm()).isEqualTo(130);
        assertThat(first.maxHeartRateBpm()).isEqualTo(152);
        assertThat(first.averageCadenceRpm())
                .as("cadencia de la vuelta, no del trackpoint")
                .isEqualTo(84);
        assertThat(first.averagePowerWatts()).isEqualTo(185);
        assertThat(first.maxPowerWatts()).isEqualTo(410);

        var second = laps.get(1);
        assertThat(second.lapNumber()).isEqualTo(2);
        assertThat(second.startedAt()).isEqualTo(Instant.parse("2026-11-08T13:25:00.500Z"));
        assertThat(second.timerTimeSeconds()).isEqualTo(300.0);
        assertThat(second.averageHeartRateBpm()).isNull();
        assertThat(second.averagePowerWatts()).isNull();
    }

    @Test
    void indoorActivityKeepsPositionNullAndHasNoUnitIdInKey() {
        var activity = parse("indoor-no-position.tcx");

        assertThat(activity.points()).hasSize(2).allSatisfy(point -> {
            assertThat(point.hasPosition()).isFalse();
            assertThat(point.heartRateBpm()).isNotNull();
            assertThat(point.powerWatts()).isNotNull();
        });
        assertThat(activity.sourceActivityKey()).isEqualTo("tcx:2026-11-09T06:00:00Z");
    }

    @Test
    void rejectsFilesWithSeveralActivities() {
        assertThatThrownBy(() -> parse("multiple-activities.tcx"))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.UNSUPPORTED_FORMAT));
    }

    @Test
    void ignoresCourses() {
        var activity = parse("course-only.tcx");

        assertThat(activity.points()).isEmpty();
        assertThat(activity.laps()).isEmpty();
        assertThat(activity.startedAt()).isNull();
        assertThat(activity.sourceActivityKey()).isNull();
    }

    @Test
    void usesLapStartWhenTrackpointsHaveNoTime() {
        var tcx = """
                <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
                  <Activities><Activity Sport="Biking"><Id>x</Id>
                    <Lap StartTime="2026-11-08T05:59:00-06:00"><Track>
                      <Trackpoint><HeartRateBpm><Value>100</Value></HeartRateBpm></Trackpoint>
                    </Track></Lap>
                  </Activity></Activities>
                </TrainingCenterDatabase>
                """;

        assertThat(parser.parse(stream(tcx)).startedAt()).isEqualTo(Instant.parse("2026-11-08T11:59:00Z"));
    }

    @Test
    void rejectsDtd() {
        var tcx = """
                <!DOCTYPE TrainingCenterDatabase [ <!ENTITY a "aaaaaaaaaa"> ]>
                <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"/>
                """;

        assertThatThrownBy(() -> parser.parse(stream(tcx)))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.FORBIDDEN_XML_CONSTRUCT));
    }

    @Test
    void rejectsInvalidCoordinateWithoutLeakingIt() {
        var tcx = """
                <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
                  <Activities><Activity Sport="Biking"><Id>x</Id><Lap StartTime="2026-11-08T12:00:00Z"><Track>
                    <Trackpoint><Position>
                      <LatitudeDegrees>19.123456abc</LatitudeDegrees><LongitudeDegrees>-99.0</LongitudeDegrees>
                    </Position></Trackpoint>
                  </Track></Lap></Activity></Activities>
                </TrainingCenterDatabase>
                """;

        assertThatThrownBy(() -> parser.parse(stream(tcx)))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.MALFORMED_FILE))
                .hasMessageContaining("LatitudeDegrees")
                .hasMessageNotContaining("19.123456");
    }

    @Test
    void enforcesMaximumNumberOfPoints() {
        var limited = new TcxParser(new ParserLimits(3));

        assertThatThrownBy(() -> limited.parse(Fixtures.open("tcx/with-laps.tcx")))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.TOO_MANY_POINTS));
    }

    private static ByteArrayInputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
