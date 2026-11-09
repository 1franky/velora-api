package mx.velora.api.importing.validation;

import static mx.velora.api.importing.validation.Activities.METER_IN_DEGREES;
import static mx.velora.api.importing.validation.Activities.moving;
import static mx.velora.api.importing.validation.Activities.point;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import mx.velora.api.importing.ActivityFileReader;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.ImportedActivity;
import mx.velora.api.importing.ImportedLap;
import mx.velora.api.importing.RejectionReason;
import mx.velora.api.importing.TrackPoint;
import mx.velora.api.importing.ValidationWarning;
import mx.velora.api.importing.ValidationWarning.Code;
import mx.velora.api.importing.parsing.ParserLimits;
import org.junit.jupiter.api.Test;

class ActivityValidatorTest {

    private static final Instant NOW = Instant.parse("2026-11-09T18:00:00Z");

    private final ActivityValidator validator = new ActivityValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    private static List<TrackPoint> cleanRide() {
        var points = new ArrayList<TrackPoint>();
        for (int t = 0; t < 10; t++) {
            points.add(moving(0, t, 6));
        }
        return points;
    }

    private static ImportedActivity readFixture(String path) throws IOException {
        try (var in = Objects.requireNonNull(ActivityValidatorTest.class.getResourceAsStream("/fixtures/" + path))) {
            return ActivityFileReader.withDefaultParsers(ParserLimits.DEFAULT).read(in);
        }
    }

    @Test
    void cleanActivityPassesUnchangedWithoutWarnings() {
        var activity = Activities.of(cleanRide());

        var result = validator.validate(activity);

        assertThat(result.warnings()).isEmpty();
        assertThat(result.activity()).isEqualTo(activity);
        assertThat(result.validationVersion()).isEqualTo(ActivityValidator.VERSION);
        assertThat(result.footprint().startedAt()).isEqualTo(Activities.START);
    }

    @Test
    void rejectsActivityWithoutPoints() {
        assertRejected(Activities.of(List.of()));
    }

    @Test
    void rejectsActivityWithoutAnyTimestamp() {
        assertRejected(Activities.of(List.of(point(0, null, 19.0, -99.0, null, null))));
    }

    @Test
    void rejectsTimestampsBefore1990() {
        var old = new TrackPoint(
                0, Instant.parse("1989-12-31T23:59:59Z"), null, null, null, null, null, null, null, null, null);

        assertRejected(Activities.of(List.of(old)));
    }

    @Test
    void rejectsTimestampsMoreThanOneDayInTheFuture() {
        var future = new TrackPoint(0, NOW.plusSeconds(86_401), null, null, null, null, null, null, null, null, null);

        assertRejected(Activities.of(List.of(future)));
    }

    @Test
    void toleratesSlightlyAheadDeviceClock() {
        var ahead = new TrackPoint(0, NOW.plusSeconds(3_600), null, null, null, null, null, null, null, null, null);

        assertThat(validator.validate(Activities.of(List.of(ahead))).warnings()).isEmpty();
    }

    @Test
    void removesOutOfRangeAndNullIslandCoordinatesFromFixture() throws IOException {
        var result = validator.validate(readFixture("gpx/out-of-range.gpx"));

        assertThat(result.warnings()).containsExactly(new ValidationWarning(Code.INVALID_COORDINATES, 3));
        assertThat(result.activity().points())
                .extracting(TrackPoint::hasPosition)
                .containsExactly(true, false, false, false, true);
        assertThat(result.activity().points())
                .as("el resto de la muestra se conserva")
                .allSatisfy(p -> assertThat(p.time()).isNotNull());
    }

    @Test
    void removesPositionOfGpsSpike() {
        var points = cleanRide();
        var normal = points.get(5);
        points.set(5, point(0, 5, normal.latitude() + 3_000 * METER_IN_DEGREES, -99.0, null, null));

        var result = validator.validate(Activities.of(points));

        assertThat(result.warnings()).containsExactly(new ValidationWarning(Code.GPS_JUMPS, 1));
        assertThat(result.activity().points().get(5).hasPosition()).isFalse();
        assertThat(result.activity().points().get(5).time()).isNotNull();
    }

    @Test
    void nullsNegativeDistancesSpeedsAndLapValues() {
        var points = List.of(point(0, 0, 19.0, -99.0, -5.0, 3.0), point(0, 1, 19.0, -99.0, 5.0, -1.0));
        var lap = new ImportedLap(1, Activities.START, -1.0, 1.0, 10.0, null, null, null, null, null, null, null);

        var result = validator.validate(Activities.of(points, List.of(lap)));

        assertThat(result.warnings()).containsExactly(new ValidationWarning(Code.NEGATIVE_VALUES, 3));
        assertThat(result.activity().points().get(0).distanceMeters()).isNull();
        assertThat(result.activity().points().get(0).speedMps()).isEqualTo(3.0);
        assertThat(result.activity().points().get(1).speedMps()).isNull();
        assertThat(result.activity().laps().getFirst().elapsedTimeSeconds()).isNull();
        assertThat(result.activity().laps().getFirst().timerTimeSeconds()).isEqualTo(1.0);
    }

    @Test
    void warnsAboutTimestampsGoingBackwardsButKeepsThem() {
        var points = List.of(moving(0, 0, 5), moving(0, 10, 5), moving(0, 5, 5), moving(0, 20, 5));

        var result = validator.validate(Activities.of(points));

        assertThat(result.warnings()).containsExactly(new ValidationWarning(Code.NON_MONOTONIC_TIME, 1));
        assertThat(result.activity().points()).isEqualTo(points);
    }

    @Test
    void midnightCrossingRideBelongsToTheLocalStartDate() throws IOException {
        var result = validator.validate(readFixture("gpx/midnight-crossing.gpx"));

        assertThat(result.footprint().localStartDate(ZoneId.of("America/Mexico_City")))
                .isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(result.footprint().elapsedSeconds()).isEqualTo(600.0);
    }

    @Test
    void gpxWithoutTimestampsIsRejected() throws IOException {
        var activity = readFixture("gpx/no-time.gpx");

        assertRejected(activity);
    }

    @Test
    void tcxCourseWithoutActivityIsRejected() throws IOException {
        assertRejected(readFixture("tcx/course-only.tcx"));
    }

    private void assertRejected(ImportedActivity activity) {
        assertThatThrownBy(() -> validator.validate(activity))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.INVALID_DATA));
    }
}
