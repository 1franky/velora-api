package mx.velora.api.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Bicicletas y dispositivos del usuario para la exportación de datos (ADR-028). */
@Service
public class CatalogExport {

    public record ExportedBike(
            UUID id,
            String name,
            String bikeType,
            @Nullable String brand,
            @Nullable String model,
            @Nullable BigDecimal weightKg,
            boolean active,
            Instant createdAt) {}

    public record ExportedDevice(
            UUID id,
            String displayName,
            @Nullable String manufacturer,
            @Nullable String model,
            Instant createdAt) {}

    private final JdbcClient jdbc;

    CatalogExport(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ExportedBike> bikes(UUID userId) {
        return jdbc.sql("""
                        SELECT id, name, bike_type, brand, model, weight_kg, active, created_at
                        FROM bikes WHERE user_id = :user ORDER BY created_at, id""")
                .param("user", userId)
                .query((rs, row) -> new ExportedBike(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("bike_type"),
                        rs.getString("brand"),
                        rs.getString("model"),
                        rs.getBigDecimal("weight_kg"),
                        rs.getBoolean("active"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public List<ExportedDevice> devices(UUID userId) {
        return jdbc.sql("""
                        SELECT id, display_name, manufacturer, model, created_at
                        FROM devices WHERE user_id = :user ORDER BY created_at, id""")
                .param("user", userId)
                .query((rs, row) -> new ExportedDevice(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("manufacturer"),
                        rs.getString("model"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
