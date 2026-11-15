package mx.velora.api.integration.strava;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import mx.velora.api.identity.AuthenticatedUser;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/integrations/strava} según {@code openapi/openapi-v1.yaml} (ADR-030). */
@RestController
@RequestMapping("/api/v1/integrations/strava")
class StravaController {

    record AuthorizationResponse(String authorizationUrl) {}

    record ConnectionRequest(
            @NotBlank @Size(max = 200) String code,
            @NotBlank @Size(max = 200) String state,
            @NotBlank @Size(max = 200) String scope) {}

    record DisconnectionResponse(int deletedActivities) {}

    private final StravaConnections connections;

    StravaController(StravaConnections connections) {
        this.connections = connections;
    }

    @GetMapping
    StravaConnections.Status status(@AuthenticationPrincipal AuthenticatedUser user) {
        return connections.status(user.userId());
    }

    @PostMapping("/authorization")
    AuthorizationResponse authorization(@AuthenticationPrincipal AuthenticatedUser user) {
        return new AuthorizationResponse(connections.authorizationUrl(user.userId()));
    }

    @PostMapping(path = "/connection", consumes = MediaType.APPLICATION_JSON_VALUE)
    StravaConnections.Status connect(
            @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody ConnectionRequest request) {
        return connections.connect(user.userId(), request.code(), request.state(), request.scope());
    }

    @DeleteMapping("/connection")
    DisconnectionResponse disconnect(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(name = "deleteActivities", defaultValue = "false") boolean deleteActivities) {
        return new DisconnectionResponse(connections.disconnect(user.userId(), deleteActivities));
    }
}
