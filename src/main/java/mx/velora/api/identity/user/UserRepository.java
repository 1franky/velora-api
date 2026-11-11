package mx.velora.api.identity.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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

    /** Perfil y preferencias. */
    public record Profile(
            UUID id,
            String email,
            String locale,
            String timeZone,
            String distanceUnit,
            String elevationUnit,
            int trackPrivacyMeters,
            @Nullable Integer maxHeartRateBpm) {}

    public Optional<Profile> findProfile(UUID id) {
        return jdbc.sql("""
                        SELECT id, email, locale, time_zone, distance_unit, elevation_unit, track_privacy_meters,
                               max_heart_rate_bpm
                        FROM users WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, row) -> new Profile(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("locale"),
                        rs.getString("time_zone"),
                        rs.getString("distance_unit"),
                        rs.getString("elevation_unit"),
                        rs.getInt("track_privacy_meters"),
                        (Integer) rs.getObject("max_heart_rate_bpm", Integer.class)))
                .optional();
    }

    /** Actualiza solo los valores no nulos. Devuelve {@code false} si el usuario no existe. */
    public boolean updatePreferences(
            UUID id,
            @Nullable String timeZone,
            @Nullable String distanceUnit,
            @Nullable String elevationUnit,
            @Nullable Integer trackPrivacyMeters,
            @Nullable Integer maxHeartRateBpm,
            Instant now) {
        return jdbc.sql("""
                                UPDATE users SET
                                    time_zone = coalesce(:timeZone, time_zone),
                                    distance_unit = coalesce(:distanceUnit, distance_unit),
                                    elevation_unit = coalesce(:elevationUnit, elevation_unit),
                                    track_privacy_meters = coalesce(:privacy, track_privacy_meters),
                                    -- 0 la borra; sin valor, se conserva.
                                    max_heart_rate_bpm = CASE WHEN CAST(:maxHr AS integer) IS NULL THEN max_heart_rate_bpm
                                                              WHEN :maxHr = 0 THEN NULL ELSE :maxHr END,
                                    updated_at = :now
                                WHERE id = :id
                                """)
                        .param("timeZone", timeZone, java.sql.Types.VARCHAR)
                        .param("distanceUnit", distanceUnit, java.sql.Types.VARCHAR)
                        .param("elevationUnit", elevationUnit, java.sql.Types.VARCHAR)
                        .param("privacy", trackPrivacyMeters, java.sql.Types.INTEGER)
                        .param("maxHr", maxHeartRateBpm, java.sql.Types.INTEGER)
                        .param("now", java.sql.Timestamp.from(now))
                        .param("id", id)
                        .update()
                == 1;
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
