package mx.velora.api.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Consultas del catálogo para otros módulos. Siempre acotadas al usuario: lo ajeno no existe. */
@Service
public class CatalogLookup {

    private final JdbcClient jdbc;

    CatalogLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Tipo ({@code MTB}, {@code ROAD}, {@code GRAVEL}, {@code OTHER}) de una bicicleta del usuario. */
    public Optional<String> bikeType(UUID userId, UUID bikeId) {
        return jdbc.sql("SELECT bike_type FROM bikes WHERE user_id = :user AND id = :id")
                .param("user", userId)
                .param("id", bikeId)
                .query(String.class)
                .optional();
    }

    public boolean deviceExists(UUID userId, UUID deviceId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM devices WHERE user_id = :user AND id = :id)")
                .param("user", userId)
                .param("id", deviceId)
                .query(Boolean.class)
                .single();
    }
}
