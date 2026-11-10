package mx.velora.api.importing.job;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mx.velora.api.activity.ActivityLookup;
import mx.velora.api.activity.ActivityLookup.Candidate;
import mx.velora.api.importing.ActivityFootprint;
import mx.velora.api.importing.BoundingBox;
import mx.velora.api.importing.Measurement;
import mx.velora.api.importing.MetricProvenance;
import mx.velora.api.importing.validation.DuplicateHeuristic;
import org.springframework.stereotype.Service;

/**
 * Posibles duplicados por huella (ADR-009, nivel 3): la misma regla al importar y al consultar una actividad ya
 * guardada, p. ej. la misma rodada importada en FIT, GPX y TCX. Solo informa; nunca descarta nada.
 */
@Service
public class PossibleDuplicates {

    /** Ventana de búsqueda en base de datos; la heurística aplica después su propia tolerancia de inicio. */
    static final Duration SEARCH_WINDOW = Duration.ofMinutes(5);

    private final ActivityLookup activities;

    PossibleDuplicates(ActivityLookup activities) {
        this.activities = activities;
    }

    /** Actividades del usuario parecidas a una huella nueva (durante la importación). */
    public List<UUID> near(UUID userId, ActivityFootprint footprint) {
        var result = new ArrayList<UUID>();
        for (var candidate : activities.startingNear(userId, footprint.startedAt(), SEARCH_WINDOW)) {
            if (DuplicateHeuristic.isLikelyDuplicate(footprint, footprintOf(candidate))) {
                result.add(candidate.activityId());
            }
        }
        return result;
    }

    /** Posibles duplicados de una actividad guardada; vacío si no existe o no es del usuario. */
    public Optional<List<UUID>> of(UUID userId, UUID activityId) {
        return activities
                .candidate(userId, activityId)
                .map(self -> near(userId, footprintOf(self)).stream()
                        .filter(id -> !id.equals(activityId))
                        .toList());
    }

    private static ActivityFootprint footprintOf(Candidate candidate) {
        var bbox = candidate.bbox();
        return new ActivityFootprint(
                candidate.startedAt(),
                candidate.elapsedSeconds(),
                candidate.distanceMeters() == null
                        ? null
                        : new Measurement(candidate.distanceMeters(), MetricProvenance.DEVICE_REPORTED),
                bbox == null ? null : new BoundingBox(bbox[0], bbox[1], bbox[2], bbox[3]),
                "stored");
    }
}
