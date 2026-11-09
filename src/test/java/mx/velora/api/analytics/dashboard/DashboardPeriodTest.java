package mx.velora.api.analytics.dashboard;

import static mx.velora.api.analytics.dashboard.DashboardPeriod.Kind.ALL;
import static mx.velora.api.analytics.dashboard.DashboardPeriod.Kind.MONTH;
import static mx.velora.api.analytics.dashboard.DashboardPeriod.Kind.WEEK;
import static mx.velora.api.analytics.dashboard.DashboardPeriod.Kind.YEAR;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DashboardPeriodTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void weekRunsMondayToSundayEvenWhenTheReferenceIsSunday() {
        var week = DashboardPeriod.containing(WEEK, d("2026-11-08")); // domingo

        assertThat(week).isEqualTo(new DashboardPeriod(d("2026-11-02"), d("2026-11-08")));
        assertThat(DashboardPeriod.previous(WEEK, week))
                .isEqualTo(new DashboardPeriod(d("2026-10-26"), d("2026-11-01")));
    }

    @Test
    void monthAndPreviousMonthAcrossYearBoundary() {
        var january = DashboardPeriod.containing(MONTH, d("2027-01-15"));

        assertThat(january).isEqualTo(new DashboardPeriod(d("2027-01-01"), d("2027-01-31")));
        assertThat(DashboardPeriod.previous(MONTH, january))
                .isEqualTo(new DashboardPeriod(d("2026-12-01"), d("2026-12-31")));
        assertThat(DashboardPeriod.containing(MONTH, d("2028-02-10")).to())
                .as("bisiesto")
                .isEqualTo(d("2028-02-29"));
    }

    @Test
    void yearAndAll() {
        assertThat(DashboardPeriod.containing(YEAR, d("2026-11-09")))
                .isEqualTo(new DashboardPeriod(d("2026-01-01"), d("2026-12-31")));
        assertThat(DashboardPeriod.containing(ALL, d("2026-11-09"))).isEqualTo(new DashboardPeriod(null, null));
        assertThat(DashboardPeriod.previous(ALL, new DashboardPeriod(null, null)))
                .isNull();
    }
}
