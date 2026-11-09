package mx.velora.api.analytics.dashboard;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import mx.velora.api.activity.ActivityStatistics;
import mx.velora.api.activity.ActivityStatistics.Totals;
import mx.velora.api.activity.ActivitySummary;
import mx.velora.api.identity.AuthenticatedUser;
import mx.velora.api.identity.UserTimeZones;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/dashboard/summary} según el contrato. */
@RestController
class DashboardController {

    static final int TREND_MONTHS = 12;

    record PeriodTotals(
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            long activityCount,
            BigDecimal distanceMeters,
            long movingTimeSeconds,
            BigDecimal elevationGainMeters) {

        static PeriodTotals of(DashboardPeriod period, Totals totals) {
            return new PeriodTotals(
                    period.from(),
                    period.to(),
                    totals.activityCount(),
                    totals.distanceMeters(),
                    totals.movingTimeSeconds(),
                    totals.elevationGainMeters());
        }
    }

    record MonthlyTotals(
            String month,
            long activityCount,
            BigDecimal distanceMeters,
            long movingTimeSeconds,
            BigDecimal elevationGainMeters) {}

    record DashboardSummary(
            String period,
            String timeZone,
            PeriodTotals current,
            @Nullable PeriodTotals previous,
            List<MonthlyTotals> monthlyTrend,
            @Nullable ActivitySummary latestActivity) {}

    private final ActivityStatistics statistics;
    private final UserTimeZones timeZones;
    private final Clock clock;

    DashboardController(ActivityStatistics statistics, UserTimeZones timeZones, Clock clock) {
        this.statistics = statistics;
        this.timeZones = timeZones;
        this.clock = clock;
    }

    @GetMapping("/api/v1/dashboard/summary")
    DashboardSummary summary(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "month") String period,
            @RequestParam(required = false) @Nullable LocalDate date) {
        DashboardPeriod.Kind kind;
        try {
            kind = DashboardPeriod.Kind.valueOf(period.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidPeriodException();
        }
        var zone = timeZones.timeZoneOf(user.userId());
        var reference = date != null ? date : LocalDate.now(clock.withZone(zone));
        var current = DashboardPeriod.containing(kind, reference);
        var previous = DashboardPeriod.previous(kind, current);
        var lastMonth = YearMonth.from(reference);
        var trend = statistics.monthly(user.userId(), lastMonth.minusMonths(TREND_MONTHS - 1L), lastMonth).stream()
                .map(m -> new MonthlyTotals(
                        m.month().toString(),
                        m.totals().activityCount(),
                        m.totals().distanceMeters(),
                        m.totals().movingTimeSeconds(),
                        m.totals().elevationGainMeters()))
                .toList();
        return new DashboardSummary(
                kind.name().toLowerCase(Locale.ROOT),
                zone.getId(),
                PeriodTotals.of(current, statistics.totals(user.userId(), current.from(), current.to())),
                previous == null
                        ? null
                        : PeriodTotals.of(previous, statistics.totals(user.userId(), previous.from(), previous.to())),
                trend,
                statistics.latest(user.userId()).orElse(null));
    }

    static final class InvalidPeriodException extends ErrorResponseException {
        InvalidPeriodException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }
}
