package mx.velora.api.activity.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import mx.velora.api.activity.ActivitySubType;
import mx.velora.api.activity.StreamType;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Lecturas de actividades, siempre acotadas al usuario: lo ajeno no existe. */
@Repository
public class ActivityQueries {

    public record Summary(
            UUID id,
            String title,
            Instant startedAt,
            LocalDate localStartDate,
            ActivitySubType subType,
            @Nullable BigDecimal distanceMeters,
            long movingTimeSeconds,
            long elapsedTimeSeconds,
            @Nullable BigDecimal elevationGainMeters,
            @Nullable BigDecimal averageSpeedMps,
            @Nullable UUID bikeId) {}

    public record Detail(
            Summary summary,
            String timeZone,
            String sourceType,
            @Nullable UUID deviceId,
            @Nullable BigDecimal elevationLossMeters,
            @Nullable BigDecimal maxSpeedMps,
            @Nullable Integer averageHeartRateBpm,
            @Nullable Integer maxHeartRateBpm,
            @Nullable Integer averagePowerWatts,
            @Nullable Integer maxPowerWatts,
            @Nullable Integer averageCadenceRpm,
            String metricsProvenanceJson,
            boolean hasTrack,
            List<StreamType> streamTypes,
            int lapCount,
            String fileFormat,
            long fileSizeBytes,
            Instant importedAt) {}

    public record Filter(
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            @Nullable UUID bikeId,
            @Nullable ActivitySubType subType,
            @Nullable Double minDistanceMeters,
            @Nullable Double maxDistanceMeters) {}

    /** Posición de paginación: la última actividad de la página anterior. */
    public record Position(Instant startedAt, UUID id) {}

    public record Lap(
            int lapNumber,
            @Nullable Instant startedAt,
            @Nullable BigDecimal elapsedTimeSeconds,
            @Nullable BigDecimal timerTimeSeconds,
            @Nullable BigDecimal distanceMeters,
            @Nullable BigDecimal averageSpeedMps,
            @Nullable BigDecimal maxSpeedMps,
            @Nullable Integer averageHeartRateBpm,
            @Nullable Integer maxHeartRateBpm,
            @Nullable Integer averageCadenceRpm,
            @Nullable Integer averagePowerWatts,
            @Nullable Integer maxPowerWatts) {}

    public record Streams(int sampleCount, Map<StreamType, @Nullable Double[]> values) {}

    private static final String SUMMARY_COLUMNS = """
            a.id, a.title, a.started_at, a.local_start_date, a.sub_type, a.distance_meters, a.moving_time_seconds,
            a.elapsed_time_seconds, a.elevation_gain_meters, a.average_speed_mps, a.bike_id""";

    private final JdbcClient jdbc;

    ActivityQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Hasta {@code limit} actividades después de {@code after}, de la más reciente a la más antigua. */
    public List<Summary> list(UUID userId, Filter filter, @Nullable Position after, int limit) {
        var sql = new StringBuilder("SELECT " + SUMMARY_COLUMNS + " FROM activities a WHERE a.user_id = :user");
        var params = new LinkedHashMap<String, Object>();
        params.put("user", userId);
        if (filter.from() != null) {
            sql.append(" AND a.local_start_date >= :from");
            params.put("from", Date.valueOf(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND a.local_start_date <= :to");
            params.put("to", Date.valueOf(filter.to()));
        }
        if (filter.bikeId() != null) {
            sql.append(" AND a.bike_id = :bike");
            params.put("bike", filter.bikeId());
        }
        if (filter.subType() != null) {
            sql.append(" AND a.sub_type = :subType");
            params.put("subType", filter.subType().name());
        }
        if (filter.minDistanceMeters() != null) {
            sql.append(" AND a.distance_meters >= :minDistance");
            params.put("minDistance", BigDecimal.valueOf(filter.minDistanceMeters()));
        }
        if (filter.maxDistanceMeters() != null) {
            sql.append(" AND a.distance_meters <= :maxDistance");
            params.put("maxDistance", BigDecimal.valueOf(filter.maxDistanceMeters()));
        }
        if (after != null) {
            sql.append(" AND (a.started_at, a.id) < (:afterStart, :afterId)");
            params.put("afterStart", Timestamp.from(after.startedAt()));
            params.put("afterId", after.id());
        }
        sql.append(" ORDER BY a.started_at DESC, a.id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query(ActivityQueries::summary)
                .list();
    }

    public Optional<Detail> detail(UUID userId, UUID activityId) {
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + """
                        , a.time_zone_at_import, a.source_type, a.device_id, a.elevation_loss_meters, a.max_speed_mps,
                          a.average_heart_rate_bpm, a.max_heart_rate_bpm, a.average_power_watts, a.max_power_watts,
                          a.average_cadence_rpm, a.metrics_provenance::text AS provenance,
                          EXISTS (SELECT 1 FROM activity_tracks t WHERE t.activity_id = a.id) AS has_track,
                          ARRAY(SELECT s.stream_type FROM activity_streams s WHERE s.activity_id = a.id
                                ORDER BY s.stream_type) AS stream_types,
                          (SELECT count(*) FROM activity_laps l WHERE l.activity_id = a.id) AS lap_count,
                          f.format, f.file_size_bytes, f.imported_at
                        FROM activities a JOIN activity_files f ON f.activity_id = a.id
                        WHERE a.user_id = :user AND a.id = :id
                        """)
                .param("user", userId)
                .param("id", activityId)
                .query((rs, n) -> new Detail(
                        summary(rs, n),
                        rs.getString("time_zone_at_import"),
                        rs.getString("source_type"),
                        rs.getObject("device_id", UUID.class),
                        rs.getBigDecimal("elevation_loss_meters"),
                        rs.getBigDecimal("max_speed_mps"),
                        integer(rs, "average_heart_rate_bpm"),
                        integer(rs, "max_heart_rate_bpm"),
                        integer(rs, "average_power_watts"),
                        integer(rs, "max_power_watts"),
                        integer(rs, "average_cadence_rpm"),
                        rs.getString("provenance"),
                        rs.getBoolean("has_track"),
                        streamTypes((String[]) rs.getArray("stream_types").getArray()),
                        rs.getInt("lap_count"),
                        rs.getString("format"),
                        rs.getLong("file_size_bytes"),
                        rs.getTimestamp("imported_at").toInstant()))
                .optional();
    }

    public boolean exists(UUID userId, UUID activityId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM activities WHERE user_id = :user AND id = :id)")
                .param("user", userId)
                .param("id", activityId)
                .query(Boolean.class)
                .single();
    }

    public List<Lap> laps(UUID activityId) {
        return jdbc.sql("SELECT * FROM activity_laps WHERE activity_id = :id ORDER BY lap_number")
                .param("id", activityId)
                .query((rs, n) -> new Lap(
                        rs.getInt("lap_number"),
                        rs.getTimestamp("started_at") == null
                                ? null
                                : rs.getTimestamp("started_at").toInstant(),
                        rs.getBigDecimal("elapsed_time_seconds"),
                        rs.getBigDecimal("timer_time_seconds"),
                        rs.getBigDecimal("distance_meters"),
                        rs.getBigDecimal("average_speed_mps"),
                        rs.getBigDecimal("max_speed_mps"),
                        integer(rs, "average_heart_rate_bpm"),
                        integer(rs, "max_heart_rate_bpm"),
                        integer(rs, "average_cadence_rpm"),
                        integer(rs, "average_power_watts"),
                        integer(rs, "max_power_watts")))
                .list();
    }

    /** Decodifica los streams pedidos ({@code null}: todos). */
    public Streams streams(UUID activityId, @Nullable Set<StreamType> types) {
        var values = new EnumMap<StreamType, @Nullable Double[]>(StreamType.class);
        var count = new int[] {0};
        jdbc.sql("SELECT stream_type, sample_count, encoding, payload FROM activity_streams WHERE activity_id = :id")
                .param("id", activityId)
                .query(rs -> {
                    var type = StreamType.valueOf(rs.getString("stream_type"));
                    count[0] = Math.max(count[0], rs.getInt("sample_count"));
                    if (types != null && !types.contains(type)) {
                        return;
                    }
                    if (!StreamCodec.ENCODING.equals(rs.getString("encoding"))) {
                        throw new IllegalStateException("Codificación de stream desconocida");
                    }
                    values.put(type, StreamCodec.decode(rs.getBytes("payload"), type.scale()));
                });
        return new Streams(count[0], values);
    }

    private static Summary summary(ResultSet rs, int row) throws SQLException {
        return new Summary(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getDate("local_start_date").toLocalDate(),
                ActivitySubType.valueOf(rs.getString("sub_type")),
                rs.getBigDecimal("distance_meters"),
                Math.round(rs.getDouble("moving_time_seconds")),
                Math.round(rs.getDouble("elapsed_time_seconds")),
                rs.getBigDecimal("elevation_gain_meters"),
                rs.getBigDecimal("average_speed_mps"),
                rs.getObject("bike_id", UUID.class));
    }

    private static @Nullable Integer integer(ResultSet rs, String column) throws SQLException {
        var value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static List<StreamType> streamTypes(String[] names) {
        var list = new ArrayList<StreamType>();
        for (var name : names) {
            list.add(StreamType.valueOf(name));
        }
        return list;
    }
}
