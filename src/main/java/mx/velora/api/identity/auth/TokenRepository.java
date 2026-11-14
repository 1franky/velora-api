package mx.velora.api.identity.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistencia de sesiones y tokens. Solo maneja hashes; nunca recibe ni devuelve tokens en claro. */
@Repository
public class TokenRepository {

    enum Kind {
        ACCESS,
        REFRESH
    }

    /** Estado de un refresh token bloqueado para rotación. */
    record RefreshState(
            UUID sessionId,
            UUID userId,
            Instant expiresAt,
            @Nullable Instant usedAt,
            boolean sessionRevoked) {}

    private final JdbcClient jdbc;

    TokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID createSession(UUID userId, Instant now) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO auth_sessions (id, user_id, created_at) VALUES (:id, :user, :now)")
                .param("id", id)
                .param("user", userId)
                .param("now", Timestamp.from(now))
                .update();
        return id;
    }

    void insertToken(byte[] hash, UUID sessionId, Kind kind, Instant issuedAt, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO auth_tokens (token_hash, session_id, kind, issued_at, expires_at)
                        VALUES (:hash, :session, :kind, :issued, :expires)
                        """)
                .param("hash", hash)
                .param("session", sessionId)
                .param("kind", kind.name())
                .param("issued", Timestamp.from(issuedAt))
                .param("expires", Timestamp.from(expiresAt))
                .update();
    }

    /** Usuario dueño de un access token vigente de una sesión no revocada. */
    public Optional<UUID> findUserByActiveAccessToken(byte[] hash, Instant now) {
        return jdbc.sql("""
                        SELECT s.user_id
                        FROM auth_tokens t JOIN auth_sessions s ON s.id = t.session_id
                        WHERE t.token_hash = :hash AND t.kind = 'ACCESS'
                          AND t.expires_at > :now AND s.revoked_at IS NULL
                        """)
                .param("hash", hash)
                .param("now", Timestamp.from(now))
                .query(UUID.class)
                .optional();
    }

    /** Bloquea la fila del refresh token hasta el fin de la transacción para serializar rotaciones. */
    Optional<RefreshState> lockRefreshToken(byte[] hash) {
        return jdbc.sql("""
                        SELECT t.session_id, s.user_id, t.expires_at, t.used_at, s.revoked_at IS NOT NULL AS revoked
                        FROM auth_tokens t JOIN auth_sessions s ON s.id = t.session_id
                        WHERE t.token_hash = :hash AND t.kind = 'REFRESH'
                        FOR UPDATE OF t
                        """)
                .param("hash", hash)
                .query((rs, row) -> new RefreshState(
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("used_at") == null
                                ? null
                                : rs.getTimestamp("used_at").toInstant(),
                        rs.getBoolean("revoked")))
                .optional();
    }

    Optional<UUID> findSessionOfRefreshToken(byte[] hash) {
        return jdbc.sql("SELECT session_id FROM auth_tokens WHERE token_hash = :hash AND kind = 'REFRESH'")
                .param("hash", hash)
                .query(UUID.class)
                .optional();
    }

    void markUsed(byte[] hash, Instant now) {
        jdbc.sql("UPDATE auth_tokens SET used_at = :now WHERE token_hash = :hash")
                .param("now", Timestamp.from(now))
                .param("hash", hash)
                .update();
    }

    void revokeSession(UUID sessionId, String reason, Instant now) {
        jdbc.sql("""
                        UPDATE auth_sessions SET revoked_at = :now, revoked_reason = :reason
                        WHERE id = :id AND revoked_at IS NULL
                        """)
                .param("now", Timestamp.from(now))
                .param("reason", reason)
                .param("id", sessionId)
                .update();
    }

    /** Borra los tokens que expiraron antes de {@code cutoff}; ya no autentican ni sirven para detectar reutilización. */
    int deleteTokensExpiredBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM auth_tokens WHERE expires_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    /** Borra las sesiones creadas antes de {@code cutoff} que ya no tienen ningún token. */
    int deleteEmptySessionsCreatedBefore(Instant cutoff) {
        return jdbc.sql("""
                        DELETE FROM auth_sessions s
                        WHERE s.created_at < :cutoff
                          AND NOT EXISTS (SELECT 1 FROM auth_tokens t WHERE t.session_id = s.id)
                        """).param("cutoff", Timestamp.from(cutoff)).update();
    }
}
