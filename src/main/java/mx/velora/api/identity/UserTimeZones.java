package mx.velora.api.identity;

import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Zona horaria configurada por el usuario, para calcular fechas deportivas locales en otros módulos. */
@Service
public class UserTimeZones {

    /** Valor inicial de {@code AGENTS.md} si el usuario no existe o la zona guardada no es válida. */
    public static final ZoneId DEFAULT = ZoneId.of("America/Mexico_City");

    private final JdbcClient jdbc;

    UserTimeZones(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ZoneId timeZoneOf(UUID userId) {
        return jdbc.sql("SELECT time_zone FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional()
                .filter(ZoneId.getAvailableZoneIds()::contains)
                .map(ZoneId::of)
                .orElse(DEFAULT);
    }
}
