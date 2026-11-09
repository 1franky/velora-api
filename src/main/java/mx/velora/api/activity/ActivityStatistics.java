package mx.velora.api.activity;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mx.velora.api.activity.persistence.ActivityQueries;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Totales de actividades por fecha deportiva local, calculados al vuelo. Sirve al dashboard mientras no existan los
 * agregados precalculados de la Fase 5.
 */
@Service
public class ActivityStatistics {

    public record Totals(
            long activityCount, BigDecimal distanceMeters, long movingTimeSeconds, BigDecimal elevationGainMeters) {

        public static final Totals ZERO = new Totals(0, BigDecimal.ZERO, 0, BigDecimal.ZERO);
    }

    public record MonthTotals(YearMonth month, Totals totals) {}

    private final JdbcClient jdbc;
    private final ActivityQueries queries;

    ActivityStatistics(JdbcClient jdbc, ActivityQueries queries) {
        this.jdbc = jdbc;
        this.queries = queries;
    }

    /** Totales entre dos fechas locales incluidas; sin límites, de todo el historial. */
    public Totals totals(UUID userId, @Nullable LocalDate from, @Nullable LocalDate to) {
        var sql = new StringBuilder("""
                SELECT count(*) AS n, coalesce(sum(distance_meters), 0) AS distance,
                       coalesce(sum(moving_time_seconds), 0) AS moving, coalesce(sum(elevation_gain_meters), 0) AS gain
                FROM activities WHERE user_id = :user""");
        var params = new HashMap<String, Object>();
        params.put("user", userId);
        if (from != null) {
            sql.append(" AND local_start_date >= :from");
            params.put("from", Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND local_start_date <= :to");
            params.put("to", Date.valueOf(to));
        }
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new Totals(
                        rs.getLong("n"),
                        rs.getBigDecimal("distance"),
                        Math.round(rs.getDouble("moving")),
                        rs.getBigDecimal("gain")))
                .single();
    }

    /** Totales por mes, de {@code first} a {@code last} incluidos, con ceros en los meses sin actividad. */
    public List<MonthTotals> monthly(UUID userId, YearMonth first, YearMonth last) {
        var byMonth = new HashMap<YearMonth, Totals>();
        jdbc.sql("""
                        SELECT to_char(local_start_date, 'YYYY-MM') AS month, count(*) AS n,
                               coalesce(sum(distance_meters), 0) AS distance, coalesce(sum(moving_time_seconds), 0) AS moving,
                               coalesce(sum(elevation_gain_meters), 0) AS gain
                        FROM activities
                        WHERE user_id = :user AND local_start_date >= :from AND local_start_date <= :to
                        GROUP BY 1
                        """)
                .param("user", userId)
                .param("from", Date.valueOf(first.atDay(1)))
                .param("to", Date.valueOf(last.atEndOfMonth()))
                .query(rs -> {
                    byMonth.put(
                            YearMonth.parse(rs.getString("month")),
                            new Totals(
                                    rs.getLong("n"),
                                    rs.getBigDecimal("distance"),
                                    Math.round(rs.getDouble("moving")),
                                    rs.getBigDecimal("gain")));
                });
        var result = new ArrayList<MonthTotals>();
        for (var month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            result.add(new MonthTotals(month, byMonth.getOrDefault(month, Totals.ZERO)));
        }
        return result;
    }

    /** Actividad más reciente por instante de inicio. */
    public Optional<ActivitySummary> latest(UUID userId) {
        return queries.list(userId, new ActivityQueries.Filter(null, null, null, null, null, null), null, 1).stream()
                .findFirst();
    }
}
