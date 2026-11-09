package mx.velora.api.catalog.bikes;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Acceso a {@code bikes}. Todas las operaciones se acotan por {@code user_id}. */
@Repository
class BikeRepository {

    enum BikeType {
        MTB,
        ROAD,
        GRAVEL,
        OTHER
    }

    record Bike(
            UUID id,
            String name,
            BikeType bikeType,
            @Nullable String brand,
            @Nullable String model,
            @Nullable BigDecimal weightKg,
            boolean active,
            Instant createdAt) {}

    /**
     * Cambios parciales: {@code null} significa "no tocar"; {@link Optional#empty()} en un campo opcional significa
     * "borrar".
     */
    record Changes(
            @Nullable String name,
            @Nullable BikeType bikeType,
            @Nullable Optional<String> brand,
            @Nullable Optional<String> model,
            @Nullable Optional<BigDecimal> weightKg,
            @Nullable Boolean active) {}

    private static final RowMapper<Bike> MAPPER = (rs, row) -> new Bike(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            BikeType.valueOf(rs.getString("bike_type")),
            rs.getString("brand"),
            rs.getString("model"),
            rs.getBigDecimal("weight_kg"),
            rs.getBoolean("active"),
            rs.getTimestamp("created_at").toInstant());

    private static final String COLUMNS = "id, name, bike_type, brand, model, weight_kg, active, created_at";

    private final JdbcClient jdbc;

    BikeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Bike> findAll(UUID userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM bikes WHERE user_id = :user ORDER BY created_at, id")
                .param("user", userId)
                .query(MAPPER)
                .list();
    }

    Optional<Bike> find(UUID userId, UUID bikeId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM bikes WHERE user_id = :user AND id = :id")
                .param("user", userId)
                .param("id", bikeId)
                .query(MAPPER)
                .optional();
    }

    Bike insert(
            UUID userId,
            String name,
            BikeType type,
            @Nullable String brand,
            @Nullable String model,
            @Nullable BigDecimal weightKg,
            Instant now) {
        return jdbc.sql("""
                        INSERT INTO bikes (id, user_id, name, bike_type, brand, model, weight_kg, created_at, updated_at)
                        VALUES (:id, :user, :name, :type, :brand, :model, :weight, :now, :now)
                        RETURNING\s""" + COLUMNS)
                .param("id", UUID.randomUUID())
                .param("user", userId)
                .param("name", name)
                .param("type", type.name())
                .param("brand", brand, Types.VARCHAR)
                .param("model", model, Types.VARCHAR)
                .param("weight", weightKg, Types.NUMERIC)
                .param("now", Timestamp.from(now))
                .query(MAPPER)
                .single();
    }

    Optional<Bike> update(UUID userId, UUID bikeId, Changes changes, Instant now) {
        return jdbc.sql("""
                        UPDATE bikes SET
                            name = CASE WHEN :setName THEN :name ELSE name END,
                            bike_type = CASE WHEN :setType THEN :type ELSE bike_type END,
                            brand = CASE WHEN :setBrand THEN :brand ELSE brand END,
                            model = CASE WHEN :setModel THEN :model ELSE model END,
                            weight_kg = CASE WHEN :setWeight THEN :weight ELSE weight_kg END,
                            active = CASE WHEN :setActive THEN :active ELSE active END,
                            updated_at = :now
                        WHERE user_id = :user AND id = :id
                        RETURNING\s""" + COLUMNS)
                .param("setName", changes.name() != null)
                .param("name", changes.name(), Types.VARCHAR)
                .param("setType", changes.bikeType() != null)
                .param(
                        "type",
                        changes.bikeType() == null ? null : changes.bikeType().name(),
                        Types.VARCHAR)
                .param("setBrand", changes.brand() != null)
                .param("brand", changes.brand() == null ? null : changes.brand().orElse(null), Types.VARCHAR)
                .param("setModel", changes.model() != null)
                .param("model", changes.model() == null ? null : changes.model().orElse(null), Types.VARCHAR)
                .param("setWeight", changes.weightKg() != null)
                .param(
                        "weight",
                        changes.weightKg() == null ? null : changes.weightKg().orElse(null),
                        Types.NUMERIC)
                .param("setActive", changes.active() != null)
                .param("active", changes.active(), Types.BOOLEAN)
                .param("now", Timestamp.from(now))
                .param("user", userId)
                .param("id", bikeId)
                .query(MAPPER)
                .optional();
    }

    boolean delete(UUID userId, UUID bikeId) {
        return jdbc.sql("DELETE FROM bikes WHERE user_id = :user AND id = :id")
                        .param("user", userId)
                        .param("id", bikeId)
                        .update()
                == 1;
    }
}
