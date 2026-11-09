package mx.velora.api.analytics.dashboard;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import org.jspecify.annotations.Nullable;

/**
 * Periodos del dashboard sobre fechas locales. Semanas de lunes a domingo (ISO-8601); meses y años de calendario.
 *
 * @param from primera fecha incluida; {@code null} en {@code ALL}
 * @param to última fecha incluida; {@code null} en {@code ALL}
 */
record DashboardPeriod(@Nullable LocalDate from, @Nullable LocalDate to) {

    enum Kind {
        WEEK,
        MONTH,
        YEAR,
        ALL
    }

    static DashboardPeriod containing(Kind kind, LocalDate date) {
        return switch (kind) {
            case WEEK -> {
                var monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new DashboardPeriod(monday, monday.plusDays(6));
            }
            case MONTH -> new DashboardPeriod(date.withDayOfMonth(1), date.with(TemporalAdjusters.lastDayOfMonth()));
            case YEAR -> new DashboardPeriod(date.withDayOfYear(1), date.with(TemporalAdjusters.lastDayOfYear()));
            case ALL -> new DashboardPeriod(null, null);
        };
    }

    /** Periodo anterior de la misma clase; {@code null} en {@code ALL}. */
    static @Nullable DashboardPeriod previous(Kind kind, DashboardPeriod current) {
        if (kind == Kind.ALL) {
            return null;
        }
        return containing(kind, current.from().minusDays(1));
    }
}
