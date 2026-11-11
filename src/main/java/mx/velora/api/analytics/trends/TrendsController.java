package mx.velora.api.analytics.trends;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import mx.velora.api.activity.ActivitySubType;
import mx.velora.api.identity.AuthenticatedUser;
import mx.velora.api.identity.UserTimeZones;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/analytics/timeseries} según el contrato (ADR-020). */
@RestController
class TrendsController {

    /** Más periodos no caben en una gráfica legible (10 años por semana). */
    static final int MAX_BUCKETS = 520;
    /** Periodos por omisión hasta hoy cuando no se da {@code from}. */
    static final int DEFAULT_WEEKS = 12;

    static final int DEFAULT_MONTHS = 12;

    record Totals(long activityCount, double distanceMeters, double movingTimeSeconds, double elevationGainMeters) {}

    record Response(
            String granularity,
            LocalDate from,
            LocalDate to,
            boolean includeIndoor,
            long duplicatesExcluded,
            Trends.DistanceSources distanceSources,
            Totals totals,
            List<Trends.Bucket> buckets) {}

    private final Trends trends;
    private final UserTimeZones timeZones;
    private final Clock clock;

    TrendsController(Trends trends, UserTimeZones timeZones, Clock clock) {
        this.trends = trends;
        this.timeZones = timeZones;
        this.clock = clock;
    }

    @GetMapping("/api/v1/analytics/timeseries")
    Response timeseries(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "week") String granularity,
            @RequestParam(required = false) @Nullable LocalDate from,
            @RequestParam(required = false) @Nullable LocalDate to,
            @RequestParam(defaultValue = "false") boolean includeIndoor,
            @RequestParam(required = false) @Nullable ActivitySubType subType,
            @RequestParam(required = false) @Nullable UUID bikeId) {
        Trends.Granularity kind;
        try {
            kind = Trends.Granularity.valueOf(granularity.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidTrendsQueryException();
        }
        var end = to != null ? to : LocalDate.now(clock.withZone(timeZones.timeZoneOf(user.userId())));
        var start = from != null ? from : defaultFrom(user.userId(), end, kind);
        if (start.isAfter(end) || Trends.bucketCount(start, end, kind) > MAX_BUCKETS) {
            throw new InvalidTrendsQueryException();
        }
        var series = trends.series(user.userId(), start, end, kind, new Trends.Filter(includeIndoor, subType, bikeId));
        var totals = new Totals(
                series.buckets().stream()
                        .mapToLong(Trends.Bucket::activityCount)
                        .sum(),
                series.buckets().stream()
                        .mapToDouble(Trends.Bucket::distanceMeters)
                        .sum(),
                series.buckets().stream()
                        .mapToDouble(Trends.Bucket::movingTimeSeconds)
                        .sum(),
                series.buckets().stream()
                        .mapToDouble(Trends.Bucket::elevationGainMeters)
                        .sum());
        return new Response(
                kind.name().toLowerCase(Locale.ROOT),
                start,
                end,
                includeIndoor,
                series.duplicatesExcluded(),
                series.sources(),
                totals,
                series.buckets());
    }

    /** 12 semanas, 12 meses o desde el año de la primera rodada. */
    private LocalDate defaultFrom(UUID userId, LocalDate end, Trends.Granularity kind) {
        return switch (kind) {
            case WEEK -> Trends.bucketStart(end, kind).minusWeeks(DEFAULT_WEEKS - 1L);
            case MONTH -> Trends.bucketStart(end, kind).minusMonths(DEFAULT_MONTHS - 1L);
            case YEAR -> {
                var first = trends.firstDate(userId);
                yield Trends.bucketStart(first == null || first.isAfter(end) ? end : first, kind);
            }
        };
    }

    static final class InvalidTrendsQueryException extends ErrorResponseException {
        InvalidTrendsQueryException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }
}
