package mx.velora.api.identity;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import mx.velora.api.identity.auth.AuthProblems.TooManyLoginAttemptsException;
import mx.velora.api.identity.auth.AuthProblems.UnauthenticatedException;
import mx.velora.api.identity.auth.LoginThrottle;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;

/** Cuenta del usuario para otros módulos: perfil exportable, confirmación de contraseña y borrado (ADR-028). */
@Service
public class UserAccounts {

    /** Perfil para la exportación. Nunca incluye el hash de la contraseña. */
    public record AccountProfile(
            UUID id,
            String email,
            String locale,
            String timeZone,
            String distanceUnit,
            String elevationUnit,
            int trackPrivacyMeters,
            @Nullable Integer maxHeartRateBpm,
            Instant createdAt) {}

    /** La contraseña de confirmación no coincide. 403 y no 401: la sesión sigue siendo válida. */
    public static final class WrongPasswordException extends ErrorResponseException {
        public WrongPasswordException() {
            super(HttpStatus.FORBIDDEN);
        }
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottle throttle;

    UserAccounts(JdbcClient jdbc, PasswordEncoder passwordEncoder, LoginThrottle throttle) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
    }

    public Optional<AccountProfile> profile(UUID userId) {
        return jdbc.sql("""
                        SELECT id, email, locale, time_zone, distance_unit, elevation_unit, track_privacy_meters,
                               max_heart_rate_bpm, created_at
                        FROM users WHERE id = :id""")
                .param("id", userId)
                .query((rs, row) -> new AccountProfile(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("locale"),
                        rs.getString("time_zone"),
                        rs.getString("distance_unit"),
                        rs.getString("elevation_unit"),
                        rs.getInt("track_privacy_meters"),
                        rs.getObject("max_heart_rate_bpm", Integer.class),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    /** Como {@link #profile(UUID)}, pero un usuario que ya no existe responde 401 (su sesión no es válida). */
    public AccountProfile requireProfile(UUID userId) {
        return profile(userId).orElseThrow(UnauthenticatedException::new);
    }

    /**
     * Confirma la contraseña antes de una operación irreversible. Comparte el limitador del login (por correo y por
     * IP): confirmar no sirve para adivinar contraseñas más rápido que iniciando sesión.
     *
     * @throws WrongPasswordException si no coincide
     * @throws TooManyLoginAttemptsException si hay demasiados fallos recientes
     */
    public void confirmPassword(UUID userId, String password, String clientAddress) {
        var credentials = jdbc.sql("SELECT email, password_hash FROM users WHERE id = :id")
                .param("id", userId)
                .query((rs, row) -> new String[] {rs.getString("email"), rs.getString("password_hash")})
                .optional()
                .orElseThrow(UnauthenticatedException::new);
        var keys = new String[] {"email:" + credentials[0].strip().toLowerCase(Locale.ROOT), "ip:" + clientAddress};
        throttle.retryAfter(keys).ifPresent(wait -> {
            throw new TooManyLoginAttemptsException(wait);
        });
        if (!passwordEncoder.matches(password, credentials[1])) {
            throttle.recordFailure(keys);
            throw new WrongPasswordException();
        }
        throttle.recordSuccess(keys);
    }

    /**
     * Borra el usuario. Todo lo que depende de él cae en cascada en la misma transacción: sesiones, tokens,
     * actividades, catálogo, importaciones y celdas. Los archivos originales los borra quien llama, tras confirmar.
     *
     * @return {@code false} si el usuario ya no existía
     */
    public boolean delete(UUID userId) {
        return jdbc.sql("DELETE FROM users WHERE id = :id").param("id", userId).update() > 0;
    }
}
