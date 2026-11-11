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

/**
 * {@code GET /api/v1/analytics/training-load} (ADR-020, §12): carga semanal **estimada** con el TRIMP de Edwards,
 * minutos en cada zona cardiaca por el número de la zona (Z1 × 1 … Z5 × 5). Solo con FC máxima y rodadas con FC: sin
 * ellas no se estima nada. Indicador de entrenamiento; no evalúa riesgo ni da recomendaciones médicas.
 */
@RestController
class TrainingLoadController {

    static final String METHOD = "edwards-trimp/1";
    static final int DEFAULT_WEEKS = 12;
    static final int MAX_WEEKS = 104;

    record Week(
            LocalDate start,
            LocalDate end,
            double load,
            long activitiesWithHeartRate,
            long activitiesWithoutHeartRate) {}

    record Response(
            String method,
            boolean estimated,
            @Nullable Integer maxHeartRateBpm,
            boolean includeIndoor,
            List<Week> weeks) {}

    private final JdbcClient jdbc;
    private final ActivityStreamReader streams;
    private final UserTimeZones timeZones;
    private final Clock clock;

    TrainingLoadController(JdbcClient jdbc, ActivityStreamReader streams, UserTimeZones timeZones, Clock clock) {
        this.jdbc = jdbc;
        this.streams = streams;
        this.timeZones = timeZones;
        this.clock = clock;
    }

    /** Carga de una rodada: minutos en cada zona por su número; lo que está por debajo de Z1 no suma. */
    static double load(double[] zoneSeconds) {
        var total = 0.0;
        for (int zone = 1; zone < zoneSeconds.length; zone++) {
            total += zoneSeconds[zone] / 60 * zone;
        }
        return total;
    }

    @GetMapping("/api/v1/analytics/training-load")
    Response trainingLoad(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "12") int weeks,
            @RequestParam(defaultValue = "false") boolean includeIndoor,
            @RequestParam(required = false) @Nullable ActivitySubType subType) {
        if (weeks < 1 || weeks > MAX_WEEKS) {
            throw new InvalidTrainingLoadQueryException();
        }
        var today = LocalDate.now(clock.withZone(timeZones.timeZoneOf(user.userId())));
        var lastMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var first = lastMonday.minusWeeks(weeks - 1L);
        var maxHr = jdbc.sql("SELECT max_heart_rate_bpm FROM users WHERE id = :id")
                .param("id", user.userId())
                .query((rs, n) -> (Integer) rs.getObject(1, Integer.class))
                .optional()
                .orElse(null);

        var loads = new double[weeks];
        var withHr = new long[weeks];
        var without = new long[weeks];
        var sql = new StringBuilder("""
                SELECT a.id, a.local_start_date,
                       EXISTS (SELECT 1 FROM activity_streams s
                               WHERE s.activity_id = a.id AND s.stream_type = 'HEART_RATE') AS has_hr
                FROM counted_activities a
                WHERE a.user_id = :user AND a.local_start_date BETWEEN :from AND :to""");
        var params = new HashMap<String, Object>();
        params.put("user", user.userId());
        params.put("from", Date.valueOf(first));
        params.put("to", Date.valueOf(lastMonday.plusDays(6)));
        if (!includeIndoor) {
            sql.append(" AND a.sub_type <> 'INDOOR'");
        }
        if (subType != null) {
            sql.append(" AND a.sub_type = :subType");
            params.put("subType", subType.name());
        }
        record Row(UUID id, LocalDate date, boolean hasHr) {}
        var rows = jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getDate("local_start_date").toLocalDate(),
                        rs.getBoolean("has_hr")))
                .list();
        for (var row : rows) {
            var week = (int) java.time.temporal.ChronoUnit.WEEKS.between(
                    first, row.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            if (!row.hasHr()) {
                without[week]++;
                continue;
            }
            if (maxHr == null) {
                // Tiene FC, pero sin FC máxima no hay zonas ni carga: solo se cuenta.
                withHr[week]++;
                continue;
            }
            var values = streams.read(row.id(), EnumSet.of(StreamType.TIME, StreamType.HEART_RATE));
            var seconds = new double[HeartRateZones.LOWER_PERCENT.length + 1];
            var time = values.get(StreamType.TIME);
            var hr = values.get(StreamType.HEART_RATE);
            if (time != null && hr != null && HeartRateZones.accumulate(time, hr, maxHr, seconds)) {
                loads[week] += load(seconds);
                withHr[week]++;
            } else {
                without[week]++;
            }
        }
        var result = new ArrayList<Week>();
        for (int w = 0; w < weeks; w++) {
            var start = first.plusWeeks(w);
            result.add(new Week(
                    start, start.plusDays(6), maxHr == null ? 0 : Math.round(loads[w]), withHr[w], without[w]));
        }
        return new Response(METHOD, true, maxHr, includeIndoor, result);
    }

    static final class InvalidTrainingLoadQueryException extends ErrorResponseException {
        InvalidTrainingLoadQueryException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }
}
