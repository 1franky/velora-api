package mx.velora.api.identity.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Acceso a {@code users}. Los correos se comparan sin distinguir mayúsculas. */
@Repository
public class UserRepository {

    /** Credenciales mínimas para autenticar. */
    public record Credentials(UUID userId, String passwordHash) {}

    private final JdbcClient jdbc;

    UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Credentials> findCredentialsByEmail(String email) {
        return jdbc.sql("SELECT id, password_hash FROM users WHERE lower(email) = lower(:email)")
                .param("email", email)
                .query((rs, row) -> new Credentials(rs.getObject("id", UUID.class), rs.getString("password_hash")))
                .optional();
    }

    public boolean anyUserExists() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users)")
                .query(Boolean.class)
                .single();
    }

    public void insert(UUID id, String email, String passwordHash, Instant now) {
        jdbc.sql("""
                        INSERT INTO users (id, email, password_hash, created_at, updated_at)
                        VALUES (:id, :email, :hash, :now, :now)
                        """)
                .param("id", id)
                .param("email", email)
                .param("hash", passwordHash)
                .param("now", java.sql.Timestamp.from(now))
                .update();
    }
}
