package mx.velora.api.analytics.records;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EddingtonTest {

    @Test
    void noRidesIsZeroAndNeedsOneDayOfOneKilometer() {
        assertThat(Eddington.of(List.of())).isEqualTo(new Eddington.Result(0, 0, 1));
    }

    @Test
    void textbookExample() {
        // 5 días: 10, 8, 6, 3, 2 km → E = 3 (tres días de al menos 3 km; no hay cuatro de al menos 4).
        var result = Eddington.of(List.of(10.0, 3.0, 8.0, 2.0, 6.0));
        assertThat(result.number()).isEqualTo(3);
        // Para E = 4 hacen falta 4 días de 4 km o más: hay 3 (10, 8, 6), falta 1.
        assertThat(result.daysForNext()).isEqualTo(3);
        assertThat(result.missingForNext()).isEqualTo(1);
    }

    @Test
    void aDayJustBelowTheThresholdDoesNotCount() {
        assertThat(Eddington.of(List.of(2.0, 1.999)).number()).isEqualTo(1);
        assertThat(Eddington.of(List.of(2.0, 2.0)).number()).isEqualTo(2);
    }

    @Test
    void matchesTheDefinitionOnRandomHistories() {
        var random = new Random(11);
        for (int run = 0; run < 300; run++) {
            var days = new ArrayList<Double>();
            for (int i = random.nextInt(60); i > 0; i--) {
                days.add(random.nextDouble() * 80);
            }
            var expected = 0;
            for (int e = 1; e <= days.size(); e++) {
                final int threshold = e;
                if (days.stream().filter(km -> km >= threshold).count() >= threshold) {
                    expected = e;
                }
            }
            assertThat(Eddington.of(days).number()).isEqualTo(expected);
        }
    }
}
