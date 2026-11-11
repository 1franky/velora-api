package mx.velora.api.identity;

import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Preferencia de privacidad de inicio y fin del recorrido (ADR-016), para los módulos que sirven geometrías. */
@Service
public class UserTrackPrivacy {

    /** Valores permitidos (metros a ocultar en cada extremo); 0 la desactiva. La base aplica el mismo CHECK. */
    public static final Set<Integer> ALLOWED_METERS = Set.of(0, 200, 300, 500, 1000);

    private final JdbcClient jdbc;

    UserTrackPrivacy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Metros a ocultar en cada extremo; 0 si está desactivada o el usuario no existe. */
    public int metersFor(UUID userId) {
        return jdbc.sql("SELECT track_privacy_meters FROM users WHERE id = :id")
                .param("id", userId)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }
}
