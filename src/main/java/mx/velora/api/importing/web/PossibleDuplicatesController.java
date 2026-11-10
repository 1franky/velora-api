package mx.velora.api.importing.web;

import java.util.List;
import java.util.UUID;
import mx.velora.api.activity.ActivityLookup;
import mx.velora.api.activity.ActivitySummary;
import mx.velora.api.identity.AuthenticatedUser;
import mx.velora.api.importing.job.PossibleDuplicates;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/activities/{activityId}/possible-duplicates}: actividades que parecen la misma rodada (ADR-009,
 * nivel 3), para resolverlas después de importar. Mismo criterio que la advertencia {@code POSSIBLE_DUPLICATE}.
 */
@RestController
class PossibleDuplicatesController {

    record Duplicates(List<ActivitySummary> items) {}

    private final PossibleDuplicates duplicates;
    private final ActivityLookup activities;

    PossibleDuplicatesController(PossibleDuplicates duplicates, ActivityLookup activities) {
        this.duplicates = duplicates;
        this.activities = activities;
    }

    @GetMapping("/api/v1/activities/{activityId}/possible-duplicates")
    Duplicates possibleDuplicates(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID activityId) {
        var ids =
                duplicates.of(user.userId(), activityId).orElseThrow(ReprocessController.ActivityNotFoundProblem::new);
        return new Duplicates(activities.summaries(user.userId(), ids));
    }
}
