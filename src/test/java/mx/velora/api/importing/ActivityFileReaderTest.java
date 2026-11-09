package mx.velora.api.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import mx.velora.api.importing.parsing.FitFixtures;
import mx.velora.api.importing.parsing.GpxParser;
import mx.velora.api.importing.parsing.ParserLimits;
import org.junit.jupiter.api.Test;

class ActivityFileReaderTest {

    private final ActivityFileReader reader = ActivityFileReader.withDefaultParsers(ParserLimits.DEFAULT);

    @Test
    void readsGpxRegardlessOfFileName() throws IOException {
        try (var in = Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/gpx/no-elevation.gpx"))) {
            var activity = reader.read(in);

            assertThat(activity.format()).isEqualTo(FileFormat.GPX);
            assertThat(activity.points()).hasSize(2);
            assertThat(activity.laps()).as("GPX no tiene vueltas").isEmpty();
        }
    }

    @Test
    void readsTcxWithLaps() throws IOException {
        try (var in = Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/tcx/with-laps.tcx"))) {
            var activity = reader.read(in);

            assertThat(activity.format()).isEqualTo(FileFormat.TCX);
            assertThat(activity.laps()).hasSize(2);
        }
    }

    @Test
    void readsFit() throws IOException {
        var fit = FitFixtures.activity().record(0).record(1).build();

        var activity = reader.read(new ByteArrayInputStream(fit));

        assertThat(activity.format()).isEqualTo(FileFormat.FIT);
        assertThat(activity.points()).hasSize(2);
    }

    @Test
    void rejectsRecognizedFormatWithoutRegisteredParser() {
        var gpxOnly = new ActivityFileReader(List.of(new GpxParser(ParserLimits.DEFAULT)));
        var fit = FitFixtures.activity().record(0).build();

        assertThatThrownBy(() -> gpxOnly.read(new ByteArrayInputStream(fit)))
                .isInstanceOfSatisfying(
                        ImportRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectionReason.UNSUPPORTED_FORMAT))
                .hasMessageContaining("FIT");
    }

    @Test
    void refusesTwoParsersForTheSameFormat() {
        var gpx = new GpxParser(ParserLimits.DEFAULT);

        assertThatThrownBy(() -> new ActivityFileReader(List.of(gpx, gpx)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
