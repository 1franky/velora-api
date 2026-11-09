package mx.velora.api.catalog.devices;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mx.velora.api.identity.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/devices} según {@code openapi/openapi-v1.yaml}: listar y registrar dispositivos de captura del
 * usuario. El contrato aún no define edición ni borrado.
 */
@RestController
@RequestMapping("/api/v1/devices")
class DeviceController {

    record Device(
            UUID id,
            String displayName,
            @Nullable String manufacturer,
            @Nullable String model,
            Instant createdAt) {}

    record DeviceList(List<Device> items) {}

    record DeviceCreate(
            @NotBlank @Size(max = 100) String displayName,
            @Nullable @Size(max = 100) String manufacturer,
            @Nullable @Size(max = 100) String model) {}

    private static final RowMapper<Device> MAPPER = (rs, row) -> new Device(
            rs.getObject("id", UUID.class),
            rs.getString("display_name"),
            rs.getString("manufacturer"),
            rs.getString("model"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;
    private final Clock clock;

    DeviceController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping
    DeviceList list(@AuthenticationPrincipal AuthenticatedUser user) {
        return new DeviceList(
                jdbc.sql("""
                        SELECT id, display_name, manufacturer, model, created_at
                        FROM devices WHERE user_id = :user ORDER BY created_at, id
                        """).param("user", user.userId()).query(MAPPER).list());
    }

    @PostMapping
    ResponseEntity<Device> create(
            @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody DeviceCreate body) {
        var device = jdbc.sql("""
                        INSERT INTO devices (id, user_id, display_name, manufacturer, model, created_at)
                        VALUES (:id, :user, :name, :manufacturer, :model, :now)
                        RETURNING id, display_name, manufacturer, model, created_at
                        """)
                .param("id", UUID.randomUUID())
                .param("user", user.userId())
                .param("name", body.displayName().strip())
                .param("manufacturer", blankToNull(body.manufacturer()), Types.VARCHAR)
                .param("model", blankToNull(body.model()), Types.VARCHAR)
                .param("now", Timestamp.from(clock.instant()))
                .query(MAPPER)
                .single();
        return ResponseEntity.created(URI.create("/api/v1/devices/" + device.id()))
                .body(device);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
