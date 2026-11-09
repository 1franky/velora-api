package mx.velora.api.importing.web;

import java.net.URI;
import java.time.Clock;
import java.util.UUID;
import mx.velora.api.activity.ActivityReprocessing;
import mx.velora.api.identity.AuthenticatedUser;
import mx.velora.api.importing.job.ImportJobRepository;
import mx.velora.api.importing.job.ImportProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/activities/{activityId}/reprocess}: recalcula una actividad desde su original de forma
 * asíncrona. Pedirlo con un reproceso pendiente devuelve ese mismo trabajo.
 */
@RestController
class ReprocessController {

    private final ActivityReprocessing reprocessing;
    private final ImportJobRepository jobs;
    private final ImportProperties properties;
    private final Clock clock;

    ReprocessController(
            ActivityReprocessing reprocessing, ImportJobRepository jobs, ImportProperties properties, Clock clock) {
        this.reprocessing = reprocessing;
        this.jobs = jobs;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping("/api/v1/activities/{activityId}/reprocess")
    ResponseEntity<ImportController.ImportAccepted> reprocess(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID activityId) {
        var original = reprocessing.originalOf(user.userId(), activityId).orElseThrow(ActivityNotFoundProblem::new);
        var jobId = jobs.pendingReprocess(user.userId(), activityId).orElse(null);
        if (jobId == null) {
            jobId = UUID.randomUUID();
            try {
                jobs.insertReprocess(
                        jobId,
                        user.userId(),
                        activityId,
                        original.storageKey(),
                        original.sha256(),
                        original.sizeBytes(),
                        original.bikeId(),
                        properties.maxAttempts(),
                        clock.instant());
            } catch (DuplicateKeyException e) {
                // Otra solicitud simultánea creó el reproceso: se devuelve ese.
                jobId = jobs.pendingReprocess(user.userId(), activityId).orElseThrow(() -> e);
            }
        }
        var statusUrl = "/api/v1/imports/" + jobId;
        return ResponseEntity.accepted()
                .location(URI.create(statusUrl))
                .body(new ImportController.ImportAccepted(jobId, "QUEUED", statusUrl));
    }

    static final class ActivityNotFoundProblem extends ErrorResponseException {
        ActivityNotFoundProblem() {
            super(HttpStatus.NOT_FOUND);
        }
    }
}
