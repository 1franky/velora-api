package mx.velora.api.analytics.heartrate;

import java.sql.Date;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import mx.velora.api.activity.ActivityStreamReader;
import mx.velora.api.activity.ActivitySubType;
import mx.velora.api.activity.StreamType;
import mx.velora.api.identity.AuthenticatedUser;
import mx.velora.api.identity.UserTimeZones;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/analytics/heart-rate} según el contrato (ADR-020). */
@RestController
class HeartRateController {

    /** Más de esto se vuelve lento (cada rodada lee sus series): unos dos años. */
    static final int MAX_DAYS = 800;

    record Zone(int zone, int fromBpm, @Nullable Integer toBpm, double seconds) {}

    record Response(
            String algorithmVersion,
            LocalDate from,
            LocalDate to,
            boolean includeIndoor,
            @Nullable Integer maxHeartRateBpm,
            long activitiesWithHeartRate,
            long activitiesWithoutHeartRate,
            double belowZonesSeconds,
            List<Zone> zones) {}

    private final JdbcClient jdbc;
    private final ActivityStreamReader streams;
    private final UserTimeZones timeZones;
    private final Clock clock;

    HeartRateController(JdbcClient jdbc, ActivityStreamReader streams, UserTimeZones timeZones, Clock clock) {
        this.jdbc = jdbc;
        this.streams = streams;
        this.timeZones = timeZones;
        this.clock = clock;
    }

    @GetMapping("/api/v1/analytics/heart-rate")
    Response heartRate(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) @Nullable LocalDate from,
            @RequestParam(required = false) @Nullable LocalDate to,
            @RequestParam(defaultValue = "false") boolean includeIndoor,
            @RequestParam(required = false) @Nullable ActivitySubType subType) {
        var end = to != null ? to : LocalDate.now(clock.withZone(timeZones.timeZoneOf(user.userId())));
        var start = from != null
                ? from
                : end.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(11);
        if (start.isAfter(end) || start.plusDays(MAX_DAYS).isBefore(end)) {
            throw new InvalidHeartRateQueryException();
        }
        var maxHr = jdbc.sql("SELECT max_heart_rate_bpm FROM users WHERE id = :id")
                .param("id", user.userId())
                .query((rs, n) -> (Integer) rs.getObject(1, Integer.class))
                .optional()
                .orElse(null);
        var sql = new StringBuilder("""
                SELECT a.id, EXISTS (SELECT 1 FROM activity_streams s
                                     WHERE s.activity_id = a.id AND s.stream_type = 'HEART_RATE') AS has_hr
                FROM counted_activities a
                WHERE a.user_id = :user AND a.local_start_date BETWEEN :from AND :to""");
        var params = new HashMap<String, Object>();
        params.put("user", user.userId());
        params.put("from", Date.valueOf(start));
        params.put("to", Date.valueOf(end));
        if (!includeIndoor) {
            sql.append(" AND a.sub_type <> 'INDOOR'");
        }
        if (subType != null) {
            sql.append(" AND a.sub_type = :subType");
            params.put("subType", subType.name());
        }
        var withHr = new ArrayList<UUID>();
        var without = new long[1];
        jdbc.sql(sql.toString()).params(params).query(rs -> {
            if (rs.getBoolean("has_hr")) {
                withHr.add(rs.getObject("id", UUID.class));
            } else {
                without[0]++;
            }
        });
        var seconds = new double[HeartRateZones.LOWER_PERCENT.length + 1];
        var zones = new ArrayList<Zone>();
        if (maxHr != null) {
            for (var id : withHr) {
                var values = streams.read(id, EnumSet.of(StreamType.TIME, StreamType.HEART_RATE));
                var time = values.get(StreamType.TIME);
                var hr = values.get(StreamType.HEART_RATE);
                if (time != null && hr != null) {
                    HeartRateZones.accumulate(time, hr, maxHr, seconds);
                }
            }
            var lower = HeartRateZones.lowerBpm(maxHr);
            for (int z = 0; z < lower.length; z++) {
                zones.add(new Zone(
                        z + 1, lower[z], z + 1 < lower.length ? lower[z + 1] - 1 : null, Math.round(seconds[z + 1])));
            }
        }
        return new Response(
                HeartRateZones.ALGORITHM_VERSION,
                start,
                end,
                includeIndoor,
                maxHr,
                withHr.size(),
                without[0],
                Math.round(seconds[0]),
                zones);
    }

    static final class InvalidHeartRateQueryException extends ErrorResponseException {
        InvalidHeartRateQueryException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }
}
