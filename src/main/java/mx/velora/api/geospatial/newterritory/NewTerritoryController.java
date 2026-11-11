package mx.velora.api.geospatial.newterritory;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.util.UUID;
import mx.velora.api.identity.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/geo/new-territory?activityId=} según el contrato (ADR-019). */
@RestController
class NewTerritoryController {

    static final double DEFAULT_TOLERANCE = 25;
    static final double MIN_TOLERANCE = 10;
    static final double MAX_TOLERANCE = 100;

    record Response(
            UUID activityId,
            String algorithmVersion,
            double toleranceMeters,
            double minStretchMeters,
            long comparedActivities,
            boolean firstActivity,
            boolean trimmed,
            double analyzedDistanceMeters,
            double newDistanceMeters,
            double newFraction,
            NewTerritory.Confidence confidence,
            @JsonRawValue @Nullable String geometry) {}

    private final NewTerritoryQueries queries;

    NewTerritoryController(NewTerritoryQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/api/v1/geo/new-territory")
    Response newTerritory(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam UUID activityId,
            @RequestParam(defaultValue = "25") double toleranceMeters) {
        if (!(toleranceMeters >= MIN_TOLERANCE && toleranceMeters <= MAX_TOLERANCE)) {
            throw new InvalidToleranceException();
        }
        var subject = queries.subject(user.userId(), activityId).orElseThrow(NoTrackException::new);
        var samples = queries.samples(activityId, subject.privacyMeters(), toleranceMeters);
        var result = NewTerritory.of(samples, subject.sampleSpacingMeters());
        return new Response(
                activityId,
                NewTerritory.ALGORITHM_VERSION,
                toleranceMeters,
                NewTerritory.MIN_STRETCH_METERS,
                subject.earlierActivities(),
                subject.earlierActivities() == 0,
                subject.privacyMeters() > 0,
                round(result.analyzedMeters()),
                round(result.newMeters()),
                Math.round(result.newFraction() * 1000) / 1000.0,
                result.confidence(),
                NewTerritory.geometryJson(result.stretches()));
    }

    private static double round(double meters) {
        return Math.round(meters * 10) / 10.0;
    }

    static final class InvalidToleranceException extends ErrorResponseException {
        InvalidToleranceException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }

    static final class NoTrackException extends ErrorResponseException {
        NoTrackException() {
            super(HttpStatus.NOT_FOUND);
        }
    }
}
