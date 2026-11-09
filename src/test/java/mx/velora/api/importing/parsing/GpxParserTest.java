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

class GpxParserTest {

    private final GpxParser parser = new GpxParser(ParserLimits.DEFAULT);

    private ImportedActivity parse(String fixture) {
        return parser.parse(Fixtures.open("gpx/" + fixture));
    }

    @Test
    void readsTrackWithTimeElevationAndSensorExtensions() {
        var activity = parse("with-time-and-sensors.gpx");

        assertThat(activity.format()).isEqualTo(FileFormat.GPX);
        assertThat(activity.parser().name()).isEqualTo("velora-gpx");
        assertThat(activity.title()).isEqualTo("Rodada de prueba");
        assertThat(activity.sourceActivityKey()).isNull();
        assertThat(activity.startedAt()).isEqualTo(Instant.parse("2026-11-08T13:15:00Z"));
        assertThat(activity.points()).hasSize(3);

        var first = activity.points().getFirst();
        assertThat(first.segment()).isZero();
        assertThat(first.latitude()).isEqualTo(19.25);
        assertThat(first.longitude()).isEqualTo(-99.15);
        assertThat(first.altitudeMeters()).isEqualTo(2400.5);
        assertThat(first.heartRateBpm()).isEqualTo(128);
        assertThat(first.cadenceRpm()).isEqualTo(82);
        assertThat(first.powerWatts()).isEqualTo(180);
        assertThat(first.temperatureCelsius()).isEqualTo(14.5);
        assertThat(first.distanceMeters()).isNull();
        assertThat(first.speedMps()).isNull();

        var second = activity.points().get(1);
        assertThat(second.heartRateBpm()).as("'130.0' se acepta como entero").isEqualTo(130);
        assertThat(second.cadenceRpm()).as("sensor ausente permanece null").isNull();
        assertThat(second.powerWatts()).isNull();
    }

    @Test
    void convertsTimesWithOffsetToUtc() {
        var third = parse("with-time-and-sensors.gpx").points().get(2);

        assertThat(third.time()).isEqualTo(Instant.parse("2026-11-08T13:15:02Z"));
    }

    @Test
    void missingElevationAndSensorsStayNull() {
        var activity = parse("no-elevation.gpx");

        assertThat(activity.points()).hasSize(2).allSatisfy(point -> {
            assertThat(point.altitudeMeters()).isNull();
            assertThat(point.heartRateBpm()).isNull();
            assertThat(point.time()).isNotNull();
        });
        assertThat(activity.title()).isNull();
    }

    @Test
    void fileWithoutTimestampsHasNoStartInstant() {
        var activity = parse("no-time.gpx");

        assertThat(activity.startedAt()).isNull();
        assertThat(activity.points())
                .hasSize(2)
                .allSatisfy(point -> assertThat(point.time()).isNull());
        assertThat(activity.title()).isEqualTo("Sin tiempos");
    }

    @Test
    void keepsSegmentIndexesAndIgnoresPlannedRoutes() {
        var activity = parse("multi-segment.gpx");

        assertThat(activity.points()).extracting(TrackPoint::segment).containsExactly(0, 0, 1);
        assertThat(activity.points()).noneMatch(point -> point.latitude() == 10.0);
        assertThat(activity.title()).as("el nombre de la ruta no es el título").isNull();
    }

    @Test
    void rejectsDocumentTypeDeclarationsToPreventXxe() {
        assertThatThrownBy(() -> parse("xxe.gpx"))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.FORBIDDEN_XML_CONSTRUCT))
                .hasMessageNotContaining("localhost");
    }

    @Test
    void rejectsTruncatedFileWithLineButWithoutContent() {
        assertThatThrownBy(() -> parse("truncated.gpx"))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.MALFORMED_FILE))
                .hasMessageContaining("línea")
                .hasMessageNotContaining("99.3");
    }

    @Test
    void rejectsInvalidCoordinateWithoutLeakingIt() {
        assertThatThrownBy(() -> parse("invalid-coordinate.gpx"))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.MALFORMED_FILE))
                .hasMessageContaining("'lat'")
                .hasMessageNotContaining("19.987654")
                .hasNoCause();
    }

    @Test
    void rejectsPointWithoutLatitude() {
        var gpx = """
                <gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
                  <trkpt lon="-99.0"/>
                </trkseg></trk></gpx>
                """;

        assertThatThrownBy(() -> parser.parse(stream(gpx)))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.MALFORMED_FILE))
                .hasMessageContaining("falta 'lat'");
    }

    @Test
    void rejectsOutOfRangeIntegerSensorValue() {
        var gpx = """
                <gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
                  <trkpt lat="19.0" lon="-99.0"><extensions><hr>1e12</hr></extensions></trkpt>
                </trkseg></trk></gpx>
                """;

        assertThatThrownBy(() -> parser.parse(stream(gpx)))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.MALFORMED_FILE));
    }

    @Test
    void enforcesMaximumNumberOfPoints() {
        var limited = new GpxParser(new ParserLimits(2));

        assertThatThrownBy(() -> limited.parse(Fixtures.open("gpx/with-time-and-sensors.gpx")))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.TOO_MANY_POINTS));
    }

    @Test
    void acceptsExactlyTheMaximumNumberOfPoints() {
        var limited = new GpxParser(new ParserLimits(3));

        assertThat(limited.parse(Fixtures.open("gpx/with-time-and-sensors.gpx")).points())
                .hasSize(3);
    }

    @Test
    void treatsTimesWithoutZoneAsUtc() {
        var gpx = """
                <gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
                  <trkpt lat="19.0" lon="-99.0"><time>2026-11-08T23:59:59.500</time></trkpt>
                </trkseg></trk></gpx>
                """;

        assertThat(parser.parse(stream(gpx)).startedAt()).isEqualTo(Instant.parse("2026-11-08T23:59:59.500Z"));
    }

    private static ByteArrayInputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void gpxHasNoDeviceSummary() {
        assertThat(parse("with-time-and-sensors.gpx").reported())
                .isEqualTo(mx.velora.api.importing.ReportedSummary.EMPTY);
    }
}
