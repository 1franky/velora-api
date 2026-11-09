package mx.velora.api.activity;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Consultas de actividades para la deduplicación e idempotencia de la importación. */
@Service
public class ActivityLookup {

    /** Datos de una actividad existente para comparar huellas (ADR-009, nivel 3). */
    public record Candidate(
            UUID activityId,
            Instant startedAt,
            double elapsedSeconds,
            @Nullable Double distanceMeters,
            double @Nullable [] bbox) {}

    private final JdbcClient jdbc;

    ActivityLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Actividades del usuario con la misma clave de origen. */
    public List<UUID> withSourceKey(UUID userId, String sourceActivityKey) {
        return jdbc.sql("SELECT id FROM activities WHERE user_id = :user AND source_activity_key = :key")
                .param("user", userId)
                .param("key", sourceActivityKey)
                .query(UUID.class)
                .list();
    }

    /** Si la actividad referencia ese original: permite reconocer un registro hecho por un intento anterior. */
    public boolean usesOriginal(UUID activityId, String storageKey) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM activity_files WHERE activity_id = :id AND storage_key = :key)")
                .param("id", activityId)
                .param("key", storageKey)
                .query(Boolean.class)
                .single();
    }

    /** Actividades del usuario que empiezan dentro de {@code window} alrededor de {@code startedAt}. */
    public List<Candidate> startingNear(UUID userId, Instant startedAt, Duration window) {
        return jdbc.sql("""
                        SELECT a.id, a.started_at, a.elapsed_time_seconds, a.distance_meters,
                               ST_XMin(t.bbox) AS min_lon, ST_YMin(t.bbox) AS min_lat,
                               ST_XMax(t.bbox) AS max_lon, ST_YMax(t.bbox) AS max_lat
                        FROM activities a LEFT JOIN activity_tracks t ON t.activity_id = a.id
                        WHERE a.user_id = :user AND a.started_at BETWEEN :from AND :to
                        """)
                .param("user", userId)
                .param("from", Timestamp.from(startedAt.minus(window)))
                .param("to", Timestamp.from(startedAt.plus(window)))
                .query((rs, n) -> new Candidate(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getDouble("elapsed_time_seconds"),
                        rs.getObject("distance_meters") == null ? null : rs.getDouble("distance_meters"),
                        rs.getObject("min_lat") == null
                                ? null
                                : new double[] {
                                    rs.getDouble("min_lat"),
                                    rs.getDouble("min_lon"),
                                    rs.getDouble("max_lat"),
                                    rs.getDouble("max_lon")
                                }))
                .list();
    }
}
