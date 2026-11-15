package mx.velora.api.integration.strava;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistencia de la conexión con Strava y de los estados OAuth. Los tokens llegan y salen cifrados. */
@Repository
class StravaConnectionRepository {

    record Row(
            UUID userId,
            long athleteId,
            String scope,
            byte[] accessToken,
            byte[] refreshToken,
            Instant accessExpiresAt,
            Instant connectedAt) {}

    private final JdbcClient jdbc;

    StravaConnectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void saveState(byte[] hash, UUID userId, Instant expiresAt) {
        jdbc.sql("INSERT INTO strava_oauth_states (state_hash, user_id, expires_at) VALUES (:hash, :user, :expires)")
                .param("hash", hash)
                .param("user", userId)
                .param("expires", Timestamp.from(expiresAt))
                .update();
    }

    /** Consume el estado: solo sirve una vez, para su usuario y antes de vencer. Limpia los vencidos de paso. */
    boolean consumeState(byte[] hash, UUID userId, Instant now) {
        var consumed = jdbc.sql("""
                        DELETE FROM strava_oauth_states
                        WHERE state_hash = :hash AND user_id = :user AND expires_at > :now
                        """)
                        .param("hash", hash)
                        .param("user", userId)
                        .param("now", Timestamp.from(now))
                        .update()
                > 0;
        jdbc.sql("DELETE FROM strava_oauth_states WHERE expires_at <= :now")
                .param("now", Timestamp.from(now))
                .update();
        return consumed;
    }

    Optional<Row> find(UUID userId) {
        return jdbc.sql("""
                        SELECT user_id, athlete_id, scope, access_token, refresh_token, access_expires_at, connected_at
                        FROM strava_connections WHERE user_id = :user
                        """)
                .param("user", userId)
                .query((rs, n) -> new Row(
                        rs.getObject("user_id", UUID.class),
                        rs.getLong("athlete_id"),
                        rs.getString("scope"),
                        rs.getBytes("access_token"),
                        rs.getBytes("refresh_token"),
                        rs.getTimestamp("access_expires_at").toInstant(),
                        rs.getTimestamp("connected_at").toInstant()))
                .optional();
    }

    /** @return el usuario de Velora que ya tiene conectado este atleta, si es otro */
    Optional<UUID> ownerOfAthlete(long athleteId) {
        return jdbc.sql("SELECT user_id FROM strava_connections WHERE athlete_id = :athlete")
                .param("athlete", athleteId)
                .query(UUID.class)
                .optional();
    }

    void upsert(Row row, Instant now) {
        jdbc.sql("""
                        INSERT INTO strava_connections (user_id, athlete_id, scope, access_token, refresh_token,
                                                        access_expires_at, connected_at, updated_at)
                        VALUES (:user, :athlete, :scope, :access, :refresh, :expires, :connected, :now)
                        ON CONFLICT (user_id) DO UPDATE SET
                            athlete_id = EXCLUDED.athlete_id, scope = EXCLUDED.scope,
                            access_token = EXCLUDED.access_token, refresh_token = EXCLUDED.refresh_token,
                            access_expires_at = EXCLUDED.access_expires_at, connected_at = EXCLUDED.connected_at,
                            updated_at = EXCLUDED.updated_at
                        """)
                .param("user", row.userId())
                .param("athlete", row.athleteId())
                .param("scope", row.scope())
                .param("access", row.accessToken())
                .param("refresh", row.refreshToken())
                .param("expires", Timestamp.from(row.accessExpiresAt()))
                .param("connected", Timestamp.from(row.connectedAt()))
                .param("now", Timestamp.from(now))
                .update();
    }

    void updateTokens(UUID userId, byte[] access, byte[] refresh, Instant expiresAt, Instant now) {
        jdbc.sql("""
                        UPDATE strava_connections SET access_token = :access, refresh_token = :refresh,
                            access_expires_at = :expires, updated_at = :now
                        WHERE user_id = :user
                        """)
                .param("access", access)
                .param("refresh", refresh)
                .param("expires", Timestamp.from(expiresAt))
                .param("now", Timestamp.from(now))
                .param("user", userId)
                .update();
    }

    boolean delete(UUID userId) {
        return jdbc.sql("DELETE FROM strava_connections WHERE user_id = :user")
                        .param("user", userId)
                        .update()
                > 0;
    }
}
