package mx.velora.api.importing.parsing;

import static mx.velora.api.importing.parsing.FitFixtures.at;
import static mx.velora.api.importing.parsing.FitFixtures.semicircles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.garmin.fit.DateTime;
import com.garmin.fit.File;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Arrays;
import mx.velora.api.importing.FileFormat;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.ImportedActivity;
import mx.velora.api.importing.RejectionReason;
import mx.velora.api.importing.TrackPoint;
import org.junit.jupiter.api.Test;

class FitParserTest {

    private final FitParser parser = new FitParser(ParserLimits.DEFAULT);

    private ImportedActivity parse(byte[] fit) {
        return parser.parse(new ByteArrayInputStream(fit));
    }

    private static byte[] rideWithGpsAndSensors() {
        return FitFixtures.activity()
                .timerStart(0)
                .record(0, r -> {
                    r.setPositionLat(semicircles(19.25));
                    r.setPositionLong(semicircles(-99.15));
                    r.setEnhancedAltitude(2400.5f);
                    r.setDistance(0f);
                    r.setEnhancedSpeed(6.5f);
                    r.setHeartRate((short) 120);
                    r.setCadence((short) 80);
                    r.setPower(190);
                    r.setTemperature((byte) 14);
                })
                .record(1, r -> {
                    r.setPositionLat(semicircles(19.25005));
                    r.setPositionLong(semicircles(-99.15005));
                    r.setHeartRate((short) 121);
                })
                .lap(l -> {
                    l.setStartTime(at(0));
                    l.setTimestamp(at(1));
                    l.setTotalElapsedTime(1.5f);
                    l.setTotalTimerTime(1.0f);
                    l.setTotalDistance(6.6f);
                    l.setEnhancedAvgSpeed(6.6f);
                    l.setEnhancedMaxSpeed(6.7f);
                    l.setAvgHeartRate((short) 120);
                    l.setMaxHeartRate((short) 121);
                    l.setAvgCadence((short) 80);
                    l.setAvgPower(190);
                    l.setMaxPower(195);
                })
                .timerStop(1)
                .build();
    }

    @Test
    void readsRecordsWithPositionAndSensors() {
        var activity = parse(rideWithGpsAndSensors());

        assertThat(activity.format()).isEqualTo(FileFormat.FIT);
        assertThat(activity.parser().name()).isEqualTo("velora-fit");
        assertThat(activity.startedAt()).isEqualTo(FitFixtures.START);
        assertThat(activity.points()).hasSize(2);

        var first = activity.points().getFirst();
        assertThat(first.time()).isEqualTo(FitFixtures.START);
        assertThat(first.latitude()).isCloseTo(19.25, within(1e-6));
        assertThat(first.longitude()).isCloseTo(-99.15, within(1e-6));
        assertThat(first.altitudeMeters()).isCloseTo(2400.5, within(0.2));
        assertThat(first.distanceMeters()).isZero();
        assertThat(first.speedMps()).isCloseTo(6.5, within(0.001));
        assertThat(first.heartRateBpm()).isEqualTo(120);
        assertThat(first.cadenceRpm()).isEqualTo(80);
        assertThat(first.powerWatts()).isEqualTo(190);
        assertThat(first.temperatureCelsius()).isEqualTo(14.0);

        var second = activity.points().get(1);
        assertThat(second.heartRateBpm()).isEqualTo(121);
        assertThat(second.powerWatts()).as("campo ausente permanece null").isNull();
        assertThat(second.altitudeMeters()).isNull();
        assertThat(second.temperatureCelsius()).isNull();
    }

    @Test
    void buildsSourceKeyFromFileId() {
        var activity = parse(rideWithGpsAndSensors());

        var timeCreated = new DateTime(FitFixtures.START).getTimestamp();
        assertThat(activity.sourceActivityKey())
                .isEqualTo("fit:" + FitFixtures.MANUFACTURER_DEVELOPMENT + ":" + FitFixtures.SERIAL_NUMBER + ":"
                        + timeCreated);
    }

    @Test
    void readsLapWithElapsedAndTimerTimesSeparately() {
        var lap = parse(rideWithGpsAndSensors()).laps().getFirst();

        assertThat(lap.lapNumber()).isEqualTo(1);
        assertThat(lap.startedAt()).isEqualTo(FitFixtures.START);
        assertThat(lap.elapsedTimeSeconds()).isCloseTo(1.5, within(0.001));
        assertThat(lap.timerTimeSeconds()).isCloseTo(1.0, within(0.001));
        assertThat(lap.distanceMeters()).isCloseTo(6.6, within(0.01));
        assertThat(lap.averageSpeedMps()).isCloseTo(6.6, within(0.001));
        assertThat(lap.maxSpeedMps()).isCloseTo(6.7, within(0.001));
        assertThat(lap.averageHeartRateBpm()).isEqualTo(120);
        assertThat(lap.maxHeartRateBpm()).isEqualTo(121);
        assertThat(lap.averageCadenceRpm()).isEqualTo(80);
        assertThat(lap.averagePowerWatts()).isEqualTo(190);
        assertThat(lap.maxPowerWatts()).isEqualTo(195);
    }

    @Test
    void prefersEnhancedFieldsAndFallsBackToStandardOnes() {
        var fit = FitFixtures.activity()
                .record(0, r -> {
                    r.setAltitude(100f);
                    r.setEnhancedAltitude(200f);
                    r.setSpeed(3f);
                    r.setEnhancedSpeed(4f);
                })
                .record(1, r -> {
                    r.setAltitude(150f);
                    r.setSpeed(5f);
                })
                .build();

        var points = parse(fit).points();

        assertThat(points.get(0).altitudeMeters()).isCloseTo(200, within(0.2));
        assertThat(points.get(0).speedMps()).isCloseTo(4, within(0.001));
        assertThat(points.get(1).altitudeMeters()).isCloseTo(150, within(0.2));
        assertThat(points.get(1).speedMps()).isCloseTo(5, within(0.001));
    }

    @Test
    void indoorRecordsKeepPositionNull() {
        var fit = FitFixtures.activity()
                .record(0, r -> {
                    r.setHeartRate((short) 110);
                    r.setPower(150);
                })
                .record(1, r -> r.setPositionLat(semicircles(19.0)))
                .build();

        var points = parse(fit).points();

        assertThat(points).allSatisfy(point -> assertThat(point.hasPosition()).isFalse());
        assertThat(points.get(1).latitude())
                .as("coordenada sin pareja se descarta")
                .isNull();
        assertThat(points.getFirst().powerWatts()).isEqualTo(150);
    }

    @Test
    void timerRestartAfterPauseStartsNewSegment() {
        var fit = FitFixtures.activity()
                .timerStart(0)
                .record(0)
                .record(1)
                .timerStop(2)
                .timerStart(60)
                .record(60)
                .timerStop(61)
                .timerStart(120)
                .record(120)
                .build();

        assertThat(parse(fit).points()).extracting(TrackPoint::segment).containsExactly(0, 0, 1, 2);
    }

    @Test
    void usesLapStartWhenRecordsHaveNoTimestamp() {
        var fit = FitFixtures.activity().lap(l -> l.setStartTime(at(30))).build();

        assertThat(parse(fit).startedAt()).isEqualTo(FitFixtures.START.plusSeconds(30));
    }

    @Test
    void rejectsNonActivityFiles() {
        var course = FitFixtures.ofType(File.COURSE).record(0).build();

        assertThatThrownBy(() -> parse(course))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.UNSUPPORTED_FORMAT));
    }

    @Test
    void rejectsCorruptedFile() {
        var fit = rideWithGpsAndSensors();
        fit[fit.length / 2] ^= (byte) 0xFF;

        assertThatThrownBy(() -> parse(fit))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.MALFORMED_FILE))
                .hasNoCause();
    }

    @Test
    void rejectsFileWhoseCrcDoesNotMatch() {
        var fit = rideWithGpsAndSensors();
        fit[fit.length - 1] ^= (byte) 0x01; // Solo cambia el CRC final: la estructura sigue siendo válida.

        assertThatThrownBy(() -> parse(fit))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.MALFORMED_FILE));
    }

    @Test
    void rejectsTruncatedFile() {
        var fit = rideWithGpsAndSensors();

        assertThatThrownBy(() -> parse(Arrays.copyOf(fit, fit.length - 20)))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.MALFORMED_FILE));
    }

    @Test
    void enforcesMaximumNumberOfPoints() {
        var limited = new FitParser(new ParserLimits(1));

        assertThatThrownBy(() -> limited.parse(new ByteArrayInputStream(rideWithGpsAndSensors())))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.TOO_MANY_POINTS));
    }

    @Test
    void generatedFixtureIsDetectedAsFit() throws Exception {
        var detection = FileFormatDetector.detect(new ByteArrayInputStream(rideWithGpsAndSensors()));

        assertThat(detection.format()).isEqualTo(FileFormat.FIT);
    }

    @Test
    void timestampsAreUtcInstants() {
        var fit = FitFixtures.activity().record(0).build();

        assertThat(parse(fit).points().getFirst().time()).isEqualTo(Instant.parse("2026-11-08T13:15:00Z"));
    }
}
