package mx.velora.api.analytics.trends;

import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import mx.velora.api.activity.ActivitySubType;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Tendencias por semana (lunes a domingo), mes o año de fecha deportiva local (ADR-020). Cuenta una sola rodada de
 * cada grupo de posibles duplicados ({@code counted_activities}) y, por omisión, solo las de exterior.
 */
@Service
class Trends {

    enum Granularity {
        WEEK,
        MONTH,
        YEAR
    }

    record Filter(
            boolean includeIndoor,
            @Nullable ActivitySubType subType,
            @Nullable UUID bikeId) {}

    record Bucket(
            LocalDate start,
            LocalDate end,
            long activityCount,
            double distanceMeters,
            double movingTimeSeconds,
            double elevationGainMeters) {

        static Bucket empty(LocalDate start, LocalDate end) {
            return new Bucket(start, end, 0, 0, 0, 0);
        }
    }

    /** Cuántas rodadas del rango tienen la distancia del dispositivo y cuántas calculada por Velora. */
    record DistanceSources(long deviceReported, long derived) {}

    record Series(List<Bucket> buckets, long duplicatesExcluded, DistanceSources sources) {}

    private final JdbcClient jdbc;

    Trends(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static LocalDate bucketStart(LocalDate date, Granularity granularity) {
        return switch (granularity) {
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
            case YEAR -> date.withDayOfYear(1);
        };
    }

    static LocalDate next(LocalDate start, Granularity granularity) {
        return switch (granularity) {
            case WEEK -> start.plusWeeks(1);
            case MONTH -> start.plusMonths(1);
            case YEAR -> start.plusYears(1);
        };
    }

    /** Número de periodos entre dos fechas, ambos incluidos. */
    static long bucketCount(LocalDate from, LocalDate to, Granularity granularity) {
        var count = 0L;
        for (var start = bucketStart(from, granularity); !start.isAfter(to); start = next(start, granularity)) {
            count++;
        }
        return count;
    }

    /** Fecha local de la primera rodada del usuario, o {@code null} si no tiene ninguna. */
    @Nullable LocalDate firstDate(UUID userId) {
        return jdbc.sql("SELECT min(local_start_date) FROM activities WHERE user_id = :user")
                .param("user", userId)
                .query((rs, n) -> rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate())
                .single();
    }

    /** Serie completa entre {@code from} y {@code to} (incluidos), con ceros en los periodos sin rodadas. */
    Series series(UUID userId, LocalDate from, LocalDate to, Granularity granularity, Filter filter) {
        var trunc = granularity.name().toLowerCase(java.util.Locale.ROOT);
        var where = new StringBuilder(" WHERE user_id = :user AND local_start_date BETWEEN :from AND :to");
        var params = new HashMap<String, Object>();
        params.put("user", userId);
        params.put("from", Date.valueOf(from));
        params.put("to", Date.valueOf(to));
        if (!filter.includeIndoor()) {
            where.append(" AND sub_type <> 'INDOOR'");
        }
        if (filter.subType() != null) {
            where.append(" AND sub_type = :subType");
            params.put("subType", filter.subType().name());
        }
        if (filter.bikeId() != null) {
            where.append(" AND bike_id = :bike");
            params.put("bike", filter.bikeId());
        }
        var byStart = new HashMap<LocalDate, Bucket>();
        var device = new long[1];
        var derived = new long[1];
        jdbc.sql("SELECT date_trunc('" + trunc + "', local_start_date)::date AS start, count(*) AS n,"
                        + " coalesce(sum(distance_meters), 0) AS distance, coalesce(sum(moving_time_seconds), 0) AS moving,"
                        + " coalesce(sum(elevation_gain_meters), 0) AS gain,"
                        + " count(*) FILTER (WHERE metrics_provenance ->> 'distanceMeters' = 'DEVICE_REPORTED') AS device,"
                        + " count(*) FILTER (WHERE metrics_provenance ->> 'distanceMeters' = 'DERIVED') AS derived"
                        + " FROM counted_activities" + where + " GROUP BY 1")
                .params(params)
                .query(rs -> {
                    var start = rs.getDate("start").toLocalDate();
                    device[0] += rs.getLong("device");
                    derived[0] += rs.getLong("derived");
                    byStart.put(
                            start,
                            new Bucket(
                                    start,
                                    next(start, granularity).minusDays(1),
                                    rs.getLong("n"),
                                    rs.getDouble("distance"),
                                    rs.getDouble("moving"),
                                    rs.getDouble("gain")));
                });
        var buckets = new ArrayList<Bucket>();
        var counted = 0L;
        for (var start = bucketStart(from, granularity); !start.isAfter(to); start = next(start, granularity)) {
            var bucket = byStart.getOrDefault(
                    start, Bucket.empty(start, next(start, granularity).minusDays(1)));
            counted += bucket.activityCount();
            buckets.add(bucket);
        }
        var all = jdbc.sql("SELECT count(*) FROM activities" + where)
                .params(params)
                .query(Long.class)
                .single();
        return new Series(buckets, all - counted, new DistanceSources(device[0], derived[0]));
    }
}
