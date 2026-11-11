package mx.velora.api.analytics.records;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mx.velora.api.activity.ActivitySubType;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Récords y Eddington con reglas explícitas (§11, ADR-020): sobre {@code counted_activities} (un duplicado cuenta una
 * vez), solo exterior salvo que se pida el rodillo, y por día deportivo local en kilómetros.
 */
@Service
class PersonalRecords {

    static final String ALGORITHM_VERSION = "records/1";
    /** Calidad mínima para el récord de velocidad media: evita que un trayecto corto o un GPS errático lo gane. */
    static final double MIN_SPEED_DISTANCE_METERS = 10_000;

    static final double MIN_SPEED_MOVING_SECONDS = 20 * 60;

    enum Kind {
        LONGEST_DISTANCE("distance_meters", "distanceMeters", ""),
        MOST_ELEVATION("elevation_gain_meters", "elevationGainMeters", ""),
        LONGEST_MOVING_TIME("moving_time_seconds", "movingTimeSeconds", ""),
        FASTEST_AVERAGE_SPEED(
                "average_speed_mps",
                "averageSpeedMps",
                " AND distance_meters >= " + (long) MIN_SPEED_DISTANCE_METERS + " AND moving_time_seconds >= "
                        + (long) MIN_SPEED_MOVING_SECONDS);

        final String column;
        final String field;
        final String condition;

        Kind(String column, String field, String condition) {
            this.column = column;
            this.field = field;
            this.condition = condition;
        }
    }

    record Filter(boolean includeIndoor, @Nullable ActivitySubType subType) {}

    /** Récord de una actividad; {@code source}: del dispositivo o calculado (null si no se sabe). */
    record ActivityRecord(
            Kind kind,
            double value,
            UUID activityId,
            String title,
            LocalDate date,
            @Nullable String source) {}

    record DayRecord(LocalDate date, double distanceMeters, long activityCount) {}

    private final JdbcClient jdbc;

    PersonalRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private String where(Filter filter, HashMap<String, Object> params, @Nullable Integer year) {
        var sql = new StringBuilder(" WHERE user_id = :user");
        if (!filter.includeIndoor()) {
            sql.append(" AND sub_type <> 'INDOOR'");
        }
        if (filter.subType() != null) {
            sql.append(" AND sub_type = :subType");
            params.put("subType", filter.subType().name());
        }
        if (year != null) {
            sql.append(" AND local_start_date BETWEEN :from AND :to");
            params.put("from", Date.valueOf(LocalDate.of(year, 1, 1)));
            params.put("to", Date.valueOf(LocalDate.of(year, 12, 31)));
        }
        return sql.toString();
    }

    /** El mejor de cada tipo; en empate gana el primero en lograrlo. */
    List<ActivityRecord> records(UUID userId, Filter filter) {
        var result = new ArrayList<ActivityRecord>();
        for (var kind : Kind.values()) {
            var params = new HashMap<String, Object>();
            params.put("user", userId);
            var where = where(filter, params, null);
            jdbc.sql("SELECT id, title, local_start_date, " + kind.column + " AS value,"
                            + " metrics_provenance ->> '" + kind.field + "' AS source FROM counted_activities" + where
                            + " AND " + kind.column + " IS NOT NULL AND " + kind.column + " > 0" + kind.condition
                            + " ORDER BY " + kind.column + " DESC, started_at ASC LIMIT 1")
                    .params(params)
                    .query((rs, n) -> new ActivityRecord(
                            kind,
                            rs.getDouble("value"),
                            rs.getObject("id", UUID.class),
                            rs.getString("title"),
                            rs.getDate("local_start_date").toLocalDate(),
                            rs.getString("source")))
                    .optional()
                    .ifPresent(result::add);
        }
        return result;
    }

    /** Día local con más kilómetros (sumando todas sus rodadas). */
    Optional<DayRecord> biggestDay(UUID userId, Filter filter) {
        var params = new HashMap<String, Object>();
        params.put("user", userId);
        return jdbc.sql(
                        "SELECT local_start_date, sum(distance_meters) AS distance, count(*) AS n FROM counted_activities"
                                + where(filter, params, null)
                                + " AND distance_meters > 0 GROUP BY local_start_date ORDER BY distance DESC, local_start_date"
                                + " LIMIT 1")
                .params(params)
                .query((rs, n) -> new DayRecord(
                        rs.getDate("local_start_date").toLocalDate(), rs.getDouble("distance"), rs.getLong("n")))
                .optional();
    }

    /** Kilómetros de cada día local con rodadas (del año dado, o de toda la historia). */
    List<Double> dailyKilometers(UUID userId, Filter filter, @Nullable Integer year) {
        var params = new HashMap<String, Object>();
        params.put("user", userId);
        return jdbc.sql("SELECT sum(distance_meters) / 1000.0 FROM counted_activities" + where(filter, params, year)
                        + " AND distance_meters > 0 GROUP BY local_start_date")
                .params(params)
                .query(Double.class)
                .list();
    }
}
