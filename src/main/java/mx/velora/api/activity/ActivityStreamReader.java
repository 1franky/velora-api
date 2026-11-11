package mx.velora.api.activity;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import mx.velora.api.activity.persistence.ActivityQueries;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Lectura de series para otros módulos (p. ej. analítica). Sin posiciones: para latitud y longitud está el endpoint
 * de series, que aplica la privacidad de inicio y fin (ADR-016).
 */
@Service
public class ActivityStreamReader {

    private final ActivityQueries queries;

    ActivityStreamReader(ActivityQueries queries) {
        this.queries = queries;
    }

    public Map<StreamType, @Nullable Double[]> read(UUID activityId, EnumSet<StreamType> types) {
        if (types.contains(StreamType.LATITUDE) || types.contains(StreamType.LONGITUDE)) {
            throw new IllegalArgumentException("Las posiciones se leen con la privacidad aplicada");
        }
        return queries.streams(activityId, types).values();
    }
}
