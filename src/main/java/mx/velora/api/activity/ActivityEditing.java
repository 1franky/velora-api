package mx.velora.api.activity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Datos que el usuario puede corregir después de importar: título, tipo y bicicleta. Las métricas, el recorrido y el
 * archivo original no se editan; para recalcularlos está el reproceso.
 */
@Service
public class ActivityEditing {

    /**
     * Cambios parciales: {@code null} significa "no tocar"; en la bicicleta, {@link Optional#empty()} la quita.
     * La pertenencia de la bicicleta al usuario se valida antes de llamar.
     */
    public record Changes(
            @Nullable String title,
            @Nullable ActivitySubType subType,
            @Nullable Optional<UUID> bikeId) {

        public boolean isEmpty() {
            return title == null && subType == null && bikeId == null;
        }
    }

    private final JdbcClient jdbc;

    ActivityEditing(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Aplica los cambios a una actividad del usuario; {@code false} si no existe o es de otro usuario. */
    @Transactional
    public boolean update(UUID userId, UUID activityId, Changes changes, Instant now) {
        var sets = new ArrayList<String>();
        var params = new LinkedHashMap<String, Object>();
        if (changes.title() != null) {
            sets.add("title = :title");
            params.put("title", changes.title());
        }
        if (changes.subType() != null) {
            sets.add("sub_type = :subType");
            params.put("subType", changes.subType().name());
        }
        if (changes.bikeId() != null) {
            sets.add("bike_id = :bike");
            params.put("bike", changes.bikeId().orElse(null));
        }
        sets.add("updated_at = :now");
        params.put("now", Timestamp.from(now));
        params.put("user", userId);
        params.put("id", activityId);
        return jdbc.sql("UPDATE activities SET " + String.join(", ", sets) + " WHERE user_id = :user AND id = :id")
                        .params(params)
                        .update()
                > 0;
    }
}
