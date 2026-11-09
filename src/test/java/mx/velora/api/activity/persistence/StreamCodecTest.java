package mx.velora.api.activity.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import mx.velora.api.activity.StreamType;
import org.junit.jupiter.api.Test;

class StreamCodecTest {

    @Test
    void roundTripsWithinHalfAQuantizationStep() {
        var random = new Random(1);
        var values = new Double[10_000];
        for (int i = 0; i < values.length; i++) {
            values[i] = 19.0 + random.nextDouble();
        }

        var decoded = StreamCodec.decode(StreamCodec.encode(values, StreamType.LATITUDE.scale()), 1e7);

        assertThat(decoded).hasSize(values.length);
        for (int i = 0; i < values.length; i++) {
            assertThat(decoded[i]).isCloseTo(values[i], within(0.5e-7 + 1e-12));
        }
    }

    @Test
    void preservesMissingValuesAsNull() {
        Double[] values = {null, 120.0, null, null, 125.0, 0.0, null};

        assertThat(StreamCodec.decode(StreamCodec.encode(values, 1), 1)).containsExactly(values);
    }

    @Test
    void zeroIsNotConfusedWithMissing() {
        Double[] values = {0.0, 0.0, -3.5, 0.0};

        assertThat(StreamCodec.decode(StreamCodec.encode(values, 10), 10)).containsExactly(values);
    }

    @Test
    void handlesEmptyAndAllMissingStreams() {
        assertThat(StreamCodec.decode(StreamCodec.encode(new Double[0], 1), 1)).isEmpty();
        assertThat(StreamCodec.decode(StreamCodec.encode(new Double[3], 1), 1)).containsExactly(null, null, null);
    }

    @Test
    void timeOffsetsInMillisecondsAreExact() {
        Double[] values = {0.0, 1_000.0, 2_000.0, 1_209_600_000.0}; // hasta 14 días después

        assertThat(StreamCodec.decode(StreamCodec.encode(values, StreamType.TIME.scale()), 1))
                .containsExactly(values);
    }

    @Test
    void writesVersionByteAndRejectsUnknownVersions() {
        var encoded = StreamCodec.encode(new Double[] {1.0}, 1);
        assertThat(encoded[0]).isEqualTo((byte) 1);

        encoded[0] = 9;
        assertThatThrownBy(() -> StreamCodec.decode(encoded, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonFiniteValues() {
        assertThatThrownBy(() -> StreamCodec.encode(new Double[] {Double.NaN}, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
