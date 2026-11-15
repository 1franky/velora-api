package mx.velora.api.importing.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import mx.velora.api.importing.FileFormat;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.RejectionReason;
import mx.velora.api.importing.StravaActivityDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StravaParserTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant START = Instant.parse("2026-11-01T14:00:00Z");

    private final StravaParser parser = new StravaParser(ParserLimits.DEFAULT);

    private static StravaActivityDocument.Summary summary(String sportType, boolean trainer) {
        return new StravaActivityDocument.Summary(
                987L,
                "Rodada al Ajusco",
                sportType,
                START,
                trainer,
                3.0,
                2.0,
                20.0,
                5.0,
                7.0,
                9.0,
                140.4,
                160.6,
                85.0,
                200.2,
                400.0);
    }

    private static StravaActivityDocument.Streams streams() {
        return new StravaActivityDocument.Streams(
                List.of(0, 1, 2),
                Arrays.asList(new double[] {19.0, -99.0}, new double[] {19.0001, -99.0}, null),
                List.of(2240.0, 2241.0, 2242.0),
                List.of(0.0, 10.0, 20.0),
                Arrays.asList(140.0, null, 142.0),
                null,
                Arrays.asList(200.0, 210.0, null),
                List.of(7.0, 7.5, 8.0),
                null);
    }

    private ByteArrayInputStream json(Object document) {
        return new ByteArrayInputStream(JSON.writeValueAsBytes(document));
    }

    @Test
    void readsSamplesAlignedWithTimeAndTheSummaryAsDeviceTotals() {
        var activity = parser.parse(json(new StravaActivityDocument(
                StravaActivityDocument.FORMAT, summary("MountainBikeRide", false), streams())));

        assertThat(activity.format()).isEqualTo(FileFormat.STRAVA);
        assertThat(activity.sourceActivityKey()).isEqualTo("strava:987");
        assertThat(activity.title()).isEqualTo("Rodada al Ajusco");
        assertThat(activity.startedAt()).isEqualTo(START);
        assertThat(activity.points()).hasSize(3);
        var second = activity.points().get(1);
        assertThat(second.time()).isEqualTo(START.plusSeconds(1));
        assertThat(second.hasPosition()).isTrue();
        assertThat(second.heartRateBpm()).isNull();
        assertThat(second.powerWatts()).isEqualTo(210);
        assertThat(activity.points().get(2).hasPosition()).isFalse();
        assertThat(activity.reported().subSport()).isEqualTo("MOUNTAIN");
        assertThat(activity.reported().distanceMeters()).isEqualTo(20.0);
        assertThat(activity.reported().averageHeartRateBpm()).isEqualTo(140);
        assertThat(activity.reported().maxHeartRateBpm()).isEqualTo(161);
    }

    @Test
    void sportTypesMapToTheSubSportsActivitySubTypeKnows() {
        assertThat(StravaParser.subSport("Ride", false)).isEqualTo("ROAD");
        assertThat(StravaParser.subSport("GravelRide", false)).isEqualTo("GRAVEL_CYCLING");
        assertThat(StravaParser.subSport("VirtualRide", false)).isEqualTo("VIRTUAL_ACTIVITY");
        assertThat(StravaParser.subSport("Ride", true)).isEqualTo("INDOOR_CYCLING");
        assertThat(StravaParser.subSport("EBikeRide", false)).isNull();
    }

    @Test
    void rejectsWhatIsNotAValidDocument() {
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream("{".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.MALFORMED_FILE));
        assertThatThrownBy(() -> parser.parse(
                        json(new StravaActivityDocument("velora-strava/99", summary("Ride", false), streams()))))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.UNSUPPORTED_FORMAT));
        var empty = new StravaActivityDocument.Streams(List.of(), null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> parser.parse(
                        json(new StravaActivityDocument(StravaActivityDocument.FORMAT, summary("Ride", false), empty))))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.INVALID_DATA));
        var misaligned = new StravaActivityDocument.Streams(
                List.of(0, 1), null, List.of(1.0), null, null, null, null, null, null);
        assertThatThrownBy(() -> parser.parse(json(
                        new StravaActivityDocument(StravaActivityDocument.FORMAT, summary("Ride", false), misaligned))))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.MALFORMED_FILE));
    }

    @Test
    void theNormalReaderNeverDetectsAStravaDocument() {
        var reader = mx.velora.api.importing.ActivityFileReader.withDefaultParsers(ParserLimits.DEFAULT);
        var document = new StravaActivityDocument(StravaActivityDocument.FORMAT, summary("Ride", false), streams());
        assertThatThrownBy(() -> reader.read(json(document)))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.UNSUPPORTED_FORMAT));
    }
}
