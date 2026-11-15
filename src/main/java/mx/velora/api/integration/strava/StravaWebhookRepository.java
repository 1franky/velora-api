package mx.velora.api.integration.strava;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Eventos de webhook pendientes de procesar. */
@Repository
class StravaWebhookRepository {

    record Event(
            long id,
            long ownerId,
            String objectType,
            long objectId,
            String aspectType,
            @Nullable String title,
            @Nullable Boolean authorized,
            int attempts) {}

    private final JdbcClient jdbc;

    StravaWebhookRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(
            long ownerId,
            String objectType,
            long objectId,
            String aspectType,
            @Nullable String title,
            @Nullable Boolean authorized,
            Instant now) {
        jdbc.sql("""
                        INSERT INTO strava_webhook_events
                            (received_at, owner_id, object_type, object_id, aspect_type, title, authorized)
                        VALUES (:now, :owner, :objectType, :objectId, :aspect, :title, :authorized)
                        """)
                .param("now", Timestamp.from(now))
                .param("owner", ownerId)
                .param("objectType", objectType)
                .param("objectId", objectId)
                .param("aspect", aspectType)
                .param("title", title, Types.VARCHAR)
                .param("authorized", authorized, Types.BOOLEAN)
                .update();
    }

    List<Event> pending(int limit) {
        return jdbc.sql("""
                        SELECT id, owner_id, object_type, object_id, aspect_type, title, authorized, attempts
                        FROM strava_webhook_events WHERE processed_at IS NULL ORDER BY id LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new Event(
                        rs.getLong("id"),
                        rs.getLong("owner_id"),
                        rs.getString("object_type"),
                        rs.getLong("object_id"),
                        rs.getString("aspect_type"),
                        rs.getString("title"),
                        (Boolean) rs.getObject("authorized"),
                        rs.getInt("attempts")))
                .list();
    }

    void processed(long id, Instant now) {
        jdbc.sql("UPDATE strava_webhook_events SET processed_at = :now WHERE id = :id")
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    void retryLater(long id) {
        jdbc.sql("UPDATE strava_webhook_events SET attempts = attempts + 1 WHERE id = :id")
                .param("id", id)
                .update();
    }
}
