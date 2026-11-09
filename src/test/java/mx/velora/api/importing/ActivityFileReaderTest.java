package mx.velora.api.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
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
        }
    }

    @Test
    void rejectsRecognizedFormatWithoutParserYet() {
        var fit = new byte[] {14, 0x20, 0, 8, 0, 0, 0, 0, '.', 'F', 'I', 'T', 0, 0};

        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(fit)))
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
