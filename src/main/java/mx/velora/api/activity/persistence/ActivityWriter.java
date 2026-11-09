package mx.velora.api.activity.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import mx.velora.api.activity.Metric;
import mx.velora.api.activity.NewActivity;
import mx.velora.api.activity.NewActivity.Lap;
import mx.velora.api.activity.NewActivity.MetricValue;
import mx.velora.api.activity.StreamType;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Escritura de una actividad y sus tablas dependientes. Debe ejecutarse dentro de una transacción. */
@Repository
public class ActivityWriter {

    private final JdbcClient jdbc;

    ActivityWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Actividad que ya contiene el mismo archivo original (mismo usuario y SHA-256). */
    public Optional<UUID> findActivityWithOriginal(UUID userId, byte[] sha256) {
        return jdbc.sql("SELECT activity_id FROM activity_files WHERE user_id = :user AND sha256 = :sha")
                .param("user", userId)
                .param("sha", sha256)
                .query(UUID.class)
                .optional();
    }

    /** Valor efectivo por métrica: el del dispositivo si existe; si no, el derivado. */
    public record Effective(Map<Metric, Double> values, Map<Metric, Metric.Source> provenance) {}

    public static Effective effective(List<MetricValue> metrics) {
        var values = new java.util.EnumMap<Metric, Double>(Metric.class);
        var provenance = new java.util.EnumMap<Metric, Metric.Source>(Metric.class);
        for (var source : List.of(Metric.Source.DERIVED, Metric.Source.DEVICE_REPORTED)) {
            for (var m : metrics) {
                if (m.source() == source) {
                    values.put(m.metric(), m.value());
                    provenance.put(m.metric(), source);
                }
            }
        }
        return new Effective(values, provenance);
    }

    public void insertActivity(
            UUID id, NewActivity cmd, String title, LocalDate localStartDate, Effective effective, Instant now) {
        var v = effective.values();
        jdbc.sql("""
                        INSERT INTO activities (
                            id, user_id, bike_id, device_id, title, sub_type, started_at, time_zone_at_import,
                            local_start_date, source_type, source_activity_key,
                            elapsed_time_seconds, moving_time_seconds, distance_meters, elevation_gain_meters,
                            elevation_loss_meters, average_speed_mps, max_speed_mps, average_heart_rate_bpm,
                            max_heart_rate_bpm, average_power_watts, max_power_watts, average_cadence_rpm,
                            metrics_provenance, created_at, updated_at)
                        VALUES (
                            :id, :user, :bike, :device, :title, :subType, :startedAt, :zone,
                            :localDate, :sourceType, :sourceKey,
                            :elapsed, :moving, :distance, :gain,
                            :loss, :avgSpeed, :maxSpeed, :avgHr,
                            :maxHr, :avgPower, :maxPower, :avgCadence,
                            CAST(:provenance AS jsonb), :now, :now)
                        """)
                .param("id", id)
                .param("user", cmd.userId())
                .param("bike", cmd.bikeId(), Types.OTHER)
                .param("device", cmd.deviceId(), Types.OTHER)
                .param("title", title)
                .param("subType", cmd.subType().name())
                .param("startedAt", Timestamp.from(cmd.startedAt()))
                .param("zone", cmd.timeZone().getId())
                .param("localDate", localStartDate)
                .param("sourceType", cmd.sourceType())
                .param("sourceKey", cmd.sourceActivityKey(), Types.VARCHAR)
                .param("elapsed", decimal(v.get(Metric.ELAPSED_TIME)), Types.NUMERIC)
                .param("moving", decimal(v.get(Metric.MOVING_TIME)), Types.NUMERIC)
                .param("distance", decimal(v.get(Metric.DISTANCE)), Types.NUMERIC)
                .param("gain", decimal(v.get(Metric.ELEVATION_GAIN)), Types.NUMERIC)
                .param("loss", decimal(v.get(Metric.ELEVATION_LOSS)), Types.NUMERIC)
                .param("avgSpeed", decimal(v.get(Metric.AVERAGE_SPEED)), Types.NUMERIC)
                .param("maxSpeed", decimal(v.get(Metric.MAX_SPEED)), Types.NUMERIC)
                .param("avgHr", integer(v.get(Metric.AVERAGE_HEART_RATE)), Types.INTEGER)
                .param("maxHr", integer(v.get(Metric.MAX_HEART_RATE)), Types.INTEGER)
                .param("avgPower", integer(v.get(Metric.AVERAGE_POWER)), Types.INTEGER)
                .param("maxPower", integer(v.get(Metric.MAX_POWER)), Types.INTEGER)
                .param("avgCadence", integer(v.get(Metric.AVERAGE_CADENCE)), Types.INTEGER)
                .param("provenance", provenanceJson(effective.provenance()))
                .param("now", Timestamp.from(now))
                .update();
    }

    public void insertMetrics(UUID activityId, List<MetricValue> metrics) {
        for (var m : metrics) {
            jdbc.sql("""
                            INSERT INTO activity_metrics (activity_id, metric, source, value, algorithm_version, parameters)
                            VALUES (:activity, :metric, :source, :value, :version, CAST(:parameters AS jsonb))
                            """)
                    .param("activity", activityId)
                    .param("metric", m.metric().name())
                    .param("source", m.source().name())
                    .param("value", decimal(m.value()), Types.NUMERIC)
                    .param("version", m.algorithmVersion(), Types.VARCHAR)
                    .param("parameters", m.parameters() == null ? null : numbersJson(m.parameters()), Types.VARCHAR)
                    .update();
        }
    }

    /**
     * Track como {@code MultiLineString} con un tramo por segmento. Los segmentos con menos de dos posiciones se
     * omiten. Devuelve {@code false} si no hay ningún tramo (p. ej. rodillo).
     */
    public boolean insertTrack(UUID activityId, NewActivity.Samples samples) {
        var lat = samples.streams().get(StreamType.LATITUDE);
        var lon = samples.streams().get(StreamType.LONGITUDE);
        if (lat == null || lon == null) {
            return false;
        }
        var segments = samples.segments();
        var lines = new ArrayList<String>();
        var current = new ArrayList<String>();
        int segment = Integer.MIN_VALUE;
        int points = 0;
        for (int i = 0; i < samples.count(); i++) {
            if (lat[i] == null || lon[i] == null) {
                continue;
            }
            if (segments[i] != segment) {
                flush(current, lines);
                segment = segments[i];
            }
            current.add(lon[i] + " " + lat[i]);
        }
        flush(current, lines);
        for (var line : lines) {
            points += line.split(",").length;
        }
        if (lines.isEmpty()) {
            return false;
        }
        var wkt = "MULTILINESTRING(" + String.join(",", lines) + ")";
        jdbc.sql("""
                        WITH g AS (SELECT ST_GeomFromText(:wkt, 4326) AS geom)
                        INSERT INTO activity_tracks (activity_id, route_geom, start_point, end_point, bbox, point_count)
                        SELECT :activity, geom,
                               ST_StartPoint(ST_GeometryN(geom, 1)),
                               ST_EndPoint(ST_GeometryN(geom, ST_NumGeometries(geom))),
                               ST_MakeEnvelope(ST_XMin(geom), ST_YMin(geom), ST_XMax(geom), ST_YMax(geom), 4326),
                               :points
                        FROM g
                        """)
                .param("wkt", wkt)
                .param("activity", activityId)
                .param("points", points)
                .update();
        return true;
    }

    private static void flush(List<String> current, List<String> lines) {
        if (current.size() >= 2) {
            lines.add("(" + String.join(",", current) + ")");
        }
        current.clear();
    }

    public void insertLaps(UUID activityId, List<Lap> laps) {
        for (var lap : laps) {
            jdbc.sql("""
                            INSERT INTO activity_laps (
                                activity_id, lap_number, started_at, elapsed_time_seconds, timer_time_seconds,
                                distance_meters, average_speed_mps, max_speed_mps, average_heart_rate_bpm,
                                max_heart_rate_bpm, average_cadence_rpm, average_power_watts, max_power_watts)
                            VALUES (:activity, :n, :start, :elapsed, :timer, :distance, :avgSpeed, :maxSpeed,
                                    :avgHr, :maxHr, :avgCadence, :avgPower, :maxPower)
                            """)
                    .param("activity", activityId)
                    .param("n", lap.lapNumber())
                    .param("start", lap.startedAt() == null ? null : Timestamp.from(lap.startedAt()), Types.TIMESTAMP)
                    .param("elapsed", decimal(lap.elapsedTimeSeconds()), Types.NUMERIC)
                    .param("timer", decimal(lap.timerTimeSeconds()), Types.NUMERIC)
                    .param("distance", decimal(lap.distanceMeters()), Types.NUMERIC)
                    .param("avgSpeed", decimal(lap.averageSpeedMps()), Types.NUMERIC)
                    .param("maxSpeed", decimal(lap.maxSpeedMps()), Types.NUMERIC)
                    .param("avgHr", lap.averageHeartRateBpm(), Types.INTEGER)
                    .param("maxHr", lap.maxHeartRateBpm(), Types.INTEGER)
                    .param("avgCadence", lap.averageCadenceRpm(), Types.INTEGER)
                    .param("avgPower", lap.averagePowerWatts(), Types.INTEGER)
                    .param("maxPower", lap.maxPowerWatts(), Types.INTEGER)
                    .update();
        }
    }

    /** Guarda cada stream con al menos un valor presente. */
    public void insertStreams(UUID activityId, NewActivity.Samples samples) {
        for (var entry : samples.streams().entrySet()) {
            var values = entry.getValue();
            if (java.util.Arrays.stream(values).allMatch(java.util.Objects::isNull)) {
                continue;
            }
            jdbc.sql("""
                            INSERT INTO activity_streams (activity_id, stream_type, sample_count, encoding, payload)
                            VALUES (:activity, :type, :count, :encoding, :payload)
                            """)
                    .param("activity", activityId)
                    .param("type", entry.getKey().name())
                    .param("count", samples.count())
                    .param("encoding", StreamCodec.ENCODING)
                    .param("payload", StreamCodec.encode(values, entry.getKey().scale()))
                    .update();
        }
    }

    public void insertFile(UUID activityId, NewActivity cmd, Instant now) {
        var file = cmd.file();
        jdbc.sql("""
                        INSERT INTO activity_files (
                            id, activity_id, user_id, format, storage_key, sha256, file_size_bytes,
                            parser_name, parser_version, imported_at)
                        VALUES (:id, :activity, :user, :format, :key, :sha, :size, :parser, :version, :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("activity", activityId)
                .param("user", cmd.userId())
                .param("format", cmd.sourceType())
                .param("key", file.storageKey())
                .param("sha", file.sha256())
                .param("size", file.sizeBytes())
                .param("parser", file.parserName())
                .param("version", file.parserVersion())
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Original y referencias de una actividad del usuario, para reprocesarla. */
    public record OriginalRef(
            String storageKey,
            byte[] sha256,
            long sizeBytes,
            @Nullable UUID bikeId,
            String parserVersion) {}

    public Optional<OriginalRef> originalOf(UUID userId, UUID activityId) {
        return jdbc.sql("""
                        SELECT f.storage_key, f.sha256, f.file_size_bytes, a.bike_id, f.parser_version
                        FROM activities a JOIN activity_files f ON f.activity_id = a.id
                        WHERE a.user_id = :user AND a.id = :id
                        """)
                .param("user", userId)
                .param("id", activityId)
                .query((rs, n) -> new OriginalRef(
                        rs.getString("storage_key"),
                        rs.getBytes("sha256"),
                        rs.getLong("file_size_bytes"),
                        rs.getObject("bike_id", UUID.class),
                        rs.getString("parser_version")))
                .optional();
    }

    /** Bloquea la actividad para el reemplazo; vacío si ya no existe. Devuelve la zona horaria de importación. */
    public Optional<String> lockForReprocess(UUID activityId) {
        return jdbc.sql("SELECT time_zone_at_import FROM activities WHERE id = :id FOR UPDATE")
                .param("id", activityId)
                .query(String.class)
                .optional();
    }

    /** Copia las métricas vigentes al historial y elimina todo lo derivado del original. */
    public void archiveAndClearDerived(UUID activityId, String previousParserVersion, Instant now) {
        jdbc.sql("""
                        INSERT INTO activity_metrics_history
                            (activity_id, metric, source, value, algorithm_version, parameters, parser_version, superseded_at)
                        SELECT activity_id, metric, source, value, algorithm_version, parameters, :parser, :now
                        FROM activity_metrics WHERE activity_id = :id
                        """)
                .param("parser", previousParserVersion)
                .param("now", Timestamp.from(now))
                .param("id", activityId)
                .update();
        for (var table : List.of("activity_metrics", "activity_tracks", "activity_laps", "activity_streams")) {
            jdbc.sql("DELETE FROM " + table + " WHERE activity_id = :id")
                    .param("id", activityId)
                    .update();
        }
    }

    /** Actualiza los datos recalculados; conserva título, bici, dispositivo y zona horaria de importación. */
    public void updateRecalculated(
            UUID activityId, NewActivity cmd, LocalDate localStartDate, Effective effective, Instant now) {
        var v = effective.values();
        jdbc.sql("""
                        UPDATE activities SET
                            sub_type = :subType, started_at = :startedAt, local_start_date = :localDate,
                            source_activity_key = :sourceKey,
                            elapsed_time_seconds = :elapsed, moving_time_seconds = :moving, distance_meters = :distance,
                            elevation_gain_meters = :gain, elevation_loss_meters = :loss, average_speed_mps = :avgSpeed,
                            max_speed_mps = :maxSpeed, average_heart_rate_bpm = :avgHr, max_heart_rate_bpm = :maxHr,
                            average_power_watts = :avgPower, max_power_watts = :maxPower, average_cadence_rpm = :avgCadence,
                            metrics_provenance = CAST(:provenance AS jsonb), updated_at = :now
                        WHERE id = :id
                        """)
                .param("subType", cmd.subType().name())
                .param("startedAt", Timestamp.from(cmd.startedAt()))
                .param("localDate", localStartDate)
                .param("sourceKey", cmd.sourceActivityKey(), Types.VARCHAR)
                .param("elapsed", decimal(v.get(Metric.ELAPSED_TIME)), Types.NUMERIC)
                .param("moving", decimal(v.get(Metric.MOVING_TIME)), Types.NUMERIC)
                .param("distance", decimal(v.get(Metric.DISTANCE)), Types.NUMERIC)
                .param("gain", decimal(v.get(Metric.ELEVATION_GAIN)), Types.NUMERIC)
                .param("loss", decimal(v.get(Metric.ELEVATION_LOSS)), Types.NUMERIC)
                .param("avgSpeed", decimal(v.get(Metric.AVERAGE_SPEED)), Types.NUMERIC)
                .param("maxSpeed", decimal(v.get(Metric.MAX_SPEED)), Types.NUMERIC)
                .param("avgHr", integer(v.get(Metric.AVERAGE_HEART_RATE)), Types.INTEGER)
                .param("maxHr", integer(v.get(Metric.MAX_HEART_RATE)), Types.INTEGER)
                .param("avgPower", integer(v.get(Metric.AVERAGE_POWER)), Types.INTEGER)
                .param("maxPower", integer(v.get(Metric.MAX_POWER)), Types.INTEGER)
                .param("avgCadence", integer(v.get(Metric.AVERAGE_CADENCE)), Types.INTEGER)
                .param("provenance", provenanceJson(effective.provenance()))
                .param("now", Timestamp.from(now))
                .param("id", activityId)
                .update();
        jdbc.sql("UPDATE activity_files SET parser_name = :name, parser_version = :version WHERE activity_id = :id")
                .param("name", cmd.file().parserName())
                .param("version", cmd.file().parserVersion())
                .param("id", activityId)
                .update();
    }

    private static @Nullable BigDecimal decimal(@Nullable Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static @Nullable Integer integer(@Nullable Double value) {
        return value == null ? null : (int) Math.round(value);
    }

    private static String provenanceJson(Map<Metric, Metric.Source> provenance) {
        return provenance.entrySet().stream()
                .map(e -> "\"" + e.getKey().apiName() + "\":\"" + e.getValue().name() + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String numbersJson(Map<String, Double> numbers) {
        return numbers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    if (!e.getKey().matches("[A-Za-z0-9_]+") || !Double.isFinite(e.getValue())) {
                        throw new IllegalArgumentException("Parámetro inválido");
                    }
                    return "\"" + e.getKey() + "\":" + String.format(Locale.ROOT, "%s", e.getValue());
                })
                .collect(Collectors.joining(",", "{", "}"));
    }
}
