package mx.velora.api.activity.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import mx.velora.api.activity.NewActivity;
import mx.velora.api.activity.SecondaryRecording;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Acceso a {@code secondary_recordings} y sus series y recorrido (ADR-032). */
@Repository
public class SecondaryRecordingStore {

    private static final String COLUMNS = """
            id, source, client_recording_id, activity_id, attached_by, completeness, format, storage_key, sha256,
            file_size_bytes, started_at, ended_at, elapsed_time_seconds, distance_meters, point_count,
            average_heart_rate_bpm, max_heart_rate_bpm, received_at
            """;

    /** Datos ya resueltos de una grabación nueva. */
    public record NewRow(
            UUID id,
            UUID userId,
            String source,
            UUID clientRecordingId,
            String completeness,
            String format,
            NewActivity.OriginalFile file,
            Instant startedAt,
            Instant endedAt,
            double elapsedTimeSeconds,
            @Nullable Double distanceMeters,
            int pointCount,
            @Nullable Integer averageHeartRateBpm,
            @Nullable Integer maxHeartRateBpm,
            Instant now) {}

    private final JdbcClient jdbc;

    SecondaryRecordingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(NewRow row) {
        jdbc.sql("""
                        INSERT INTO secondary_recordings (
                            id, user_id, source, client_recording_id, completeness, format, storage_key, sha256,
                            file_size_bytes, parser_name, parser_version, started_at, ended_at, elapsed_time_seconds,
                            distance_meters, point_count, average_heart_rate_bpm, max_heart_rate_bpm, received_at,
                            updated_at)
                        VALUES (
                            :id, :user, :source, :client, :completeness, :format, :key, :sha, :size, :parser,
                            :parserVersion, :started, :ended, :elapsed, :distance, :points, :avgHr, :maxHr, :now, :now)
                        """)
                .param("id", row.id())
                .param("user", row.userId())
                .param("source", row.source())
                .param("client", row.clientRecordingId())
                .param("completeness", row.completeness())
                .param("format", row.format())
                .param("key", row.file().storageKey())
                .param("sha", row.file().sha256())
                .param("size", row.file().sizeBytes())
                .param("parser", row.file().parserName())
                .param("parserVersion", row.file().parserVersion())
                .param("started", Timestamp.from(row.startedAt()))
                .param("ended", Timestamp.from(row.endedAt()))
                .param("elapsed", BigDecimal.valueOf(row.elapsedTimeSeconds()))
                .param(
                        "distance",
                        row.distanceMeters() == null ? null : BigDecimal.valueOf(row.distanceMeters()),
                        Types.NUMERIC)
                .param("points", row.pointCount())
                .param("avgHr", row.averageHeartRateBpm(), Types.INTEGER)
                .param("maxHr", row.maxHeartRateBpm(), Types.INTEGER)
                .param("now", Timestamp.from(row.now()))
                .update();
    }

    public void insertStreams(UUID recordingId, NewActivity.Samples samples) {
        for (var entry : samples.streams().entrySet()) {
            var values = entry.getValue();
            if (Arrays.stream(values).allMatch(Objects::isNull)) {
                continue;
            }
            jdbc.sql("""
                            INSERT INTO secondary_recording_streams (recording_id, stream_type, sample_count, encoding,
                                                                     payload)
                            VALUES (:recording, :type, :count, :encoding, :payload)
                            """)
                    .param("recording", recordingId)
                    .param("type", entry.getKey().name())
                    .param("count", samples.count())
                    .param("encoding", StreamCodec.ENCODING)
                    .param("payload", StreamCodec.encode(values, entry.getKey().scale()))
                    .update();
        }
    }

    public void insertTrack(UUID recordingId, NewActivity.Samples samples) {
        ActivityWriter.trackWkt(samples)
                .ifPresent(track -> jdbc.sql("""
                        WITH g AS (SELECT ST_GeomFromText(:wkt, 4326) AS geom)
                        INSERT INTO secondary_recording_tracks (recording_id, route_geom, bbox, point_count)
                        SELECT :recording, geom,
                               ST_MakeEnvelope(ST_XMin(geom), ST_YMin(geom), ST_XMax(geom), ST_YMax(geom), 4326),
                               :points
                        FROM g
                        """)
                        .param("wkt", track.wkt())
                        .param("recording", recordingId)
                        .param("points", track.points())
                        .update());
    }

    public Optional<SecondaryRecording> find(UUID userId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM secondary_recordings WHERE user_id = :user AND id = :id")
                .param("user", userId)
                .param("id", id)
                .query(SecondaryRecordingStore::map)
                .optional();
    }

    public Optional<SecondaryRecording> byClientId(UUID userId, String source, UUID clientRecordingId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM secondary_recordings
                        WHERE user_id = :user AND source = :source AND client_recording_id = :client
                        """)
                .param("user", userId)
                .param("source", source)
                .param("client", clientRecordingId)
                .query(SecondaryRecordingStore::map)
                .optional();
    }

    public Optional<UUID> bySha256(UUID userId, byte[] sha256) {
        return jdbc.sql("SELECT id FROM secondary_recordings WHERE user_id = :user AND sha256 = :sha")
                .param("user", userId)
                .param("sha", sha256)
                .query(UUID.class)
                .optional();
    }

    /** @param assigned {@code null}: todas; {@code true}: adjuntas; {@code false}: sin asignar */
    public List<SecondaryRecording> list(UUID userId, @Nullable Boolean assigned, int offset, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM secondary_recordings WHERE user_id = :user" + filter(assigned)
                        + " ORDER BY started_at DESC, id LIMIT :limit OFFSET :offset")
                .param("user", userId)
                .param("limit", limit)
                .param("offset", offset)
                .query(SecondaryRecordingStore::map)
                .list();
    }

    public long count(UUID userId, @Nullable Boolean assigned) {
        return jdbc.sql("SELECT count(*) FROM secondary_recordings WHERE user_id = :user" + filter(assigned))
                .param("user", userId)
                .query(Long.class)
                .single();
    }

    public List<SecondaryRecording> forActivity(UUID userId, UUID activityId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM secondary_recordings
                        WHERE user_id = :user AND activity_id = :activity ORDER BY started_at, id
                        """)
                .param("user", userId)
                .param("activity", activityId)
                .query(SecondaryRecordingStore::map)
                .list();
    }

    /** Todas las del usuario, para la exportación. */
    public List<SecondaryRecording> all(UUID userId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM secondary_recordings WHERE user_id = :user ORDER BY started_at, id")
                .param("user", userId)
                .query(SecondaryRecordingStore::map)
                .list();
    }

    public boolean assign(UUID userId, UUID id, @Nullable UUID activityId, String attachedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE secondary_recordings SET activity_id = :activity, attached_by = :by, updated_at = :now
                        WHERE user_id = :user AND id = :id
                        """)
                        .param("activity", activityId, Types.OTHER)
                        .param("by", attachedBy)
                        .param("now", Timestamp.from(now))
                        .param("user", userId)
                        .param("id", id)
                        .update()
                > 0;
    }

    public boolean delete(UUID userId, UUID id) {
        return jdbc.sql("DELETE FROM secondary_recordings WHERE user_id = :user AND id = :id")
                        .param("user", userId)
                        .param("id", id)
                        .update()
                > 0;
    }

    /**
     * Actividades del usuario que se solapan con {@code [start, end]} al menos {@code minOverlap} de la más corta de
     * las dos ventanas ({@code secondary-match/1}). La de la actividad es {@code [started_at, started_at + elapsed]}.
     */
    public List<UUID> overlappingActivities(UUID userId, Instant start, Instant end, double minOverlap) {
        return jdbc.sql("""
                        WITH w AS (
                            SELECT id, extract(epoch FROM started_at) AS a_start,
                                   extract(epoch FROM started_at) + elapsed_time_seconds AS a_end
                            FROM activities
                            WHERE user_id = :user
                              AND started_at <= :end
                              AND started_at + elapsed_time_seconds * interval '1 second' >= :start)
                        SELECT id FROM w
                        WHERE least(a_end - a_start, :rEnd - :rStart) > 0
                          AND least(a_end, :rEnd) - greatest(a_start, :rStart)
                              >= :minOverlap * least(a_end - a_start, :rEnd - :rStart)
                        ORDER BY id
                        """)
                .param("user", userId)
                .param("start", Timestamp.from(start))
                .param("end", Timestamp.from(end))
                .param("rStart", epochSeconds(start))
                .param("rEnd", epochSeconds(end))
                .param("minOverlap", BigDecimal.valueOf(minOverlap))
                .query(UUID.class)
                .list();
    }

    /**
     * Grabaciones sin asignar del usuario que la automática puede mover (nunca asignadas, o asignadas por ella y
     * luego liberadas al borrarse su actividad) y que se cruzan en el tiempo con {@code [start, end]}.
     */
    public List<SecondaryRecording> unassignedAutoCandidates(UUID userId, Instant start, Instant end) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM secondary_recordings
                        WHERE user_id = :user AND activity_id IS NULL
                          AND (attached_by IS NULL OR attached_by = 'AUTO')
                          AND started_at <= :end AND ended_at >= :start
                        """)
                .param("user", userId)
                .param("start", Timestamp.from(start))
                .param("end", Timestamp.from(end))
                .query(SecondaryRecordingStore::map)
                .list();
    }

    private static String filter(@Nullable Boolean assigned) {
        if (assigned == null) {
            return "";
        }
        return assigned ? " AND activity_id IS NOT NULL" : " AND activity_id IS NULL";
    }

    private static BigDecimal epochSeconds(Instant instant) {
        return BigDecimal.valueOf(instant.getEpochSecond()).add(BigDecimal.valueOf(instant.getNano(), 9));
    }

    private static SecondaryRecording map(ResultSet rs, int row) throws SQLException {
        var activityId = rs.getObject("activity_id", UUID.class);
        var distance = rs.getBigDecimal("distance_meters");
        return new SecondaryRecording(
                rs.getObject("id", UUID.class),
                rs.getString("source"),
                rs.getObject("client_recording_id", UUID.class),
                activityId,
                activityId == null ? null : rs.getString("attached_by"),
                rs.getString("completeness"),
                rs.getString("format"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("ended_at").toInstant(),
                rs.getBigDecimal("elapsed_time_seconds").doubleValue(),
                distance == null ? null : distance.doubleValue(),
                rs.getInt("point_count"),
                (Integer) rs.getObject("average_heart_rate_bpm", Integer.class),
                (Integer) rs.getObject("max_heart_rate_bpm", Integer.class),
                rs.getTimestamp("received_at").toInstant(),
                new SecondaryRecording.Original(
                        rs.getString("storage_key"), rs.getBytes("sha256"), rs.getLong("file_size_bytes")));
    }
}
