package mx.velora.api.integration.strava;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Callback público de webhooks de Strava (sin sesión: lo llama Strava). {@code GET} valida la suscripción y
 * {@code POST} recibe eventos; el cuerpo se lee como mapa porque Strava puede agregar campos.
 */
@RestController
@RequestMapping("/api/v1/integrations/strava/webhook")
class StravaWebhookController {

    private final StravaWebhooks webhooks;

    StravaWebhookController(StravaWebhooks webhooks) {
        this.webhooks = webhooks;
    }

    @GetMapping
    ResponseEntity<Map<String, String>> validate(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        var accepted = webhooks.validate(mode, token, challenge);
        return accepted == null
                ? ResponseEntity.status(HttpStatus.FORBIDDEN).build()
                : ResponseEntity.ok(Map.of("hub.challenge", accepted));
    }

    @PostMapping
    ResponseEntity<Void> receive(@RequestBody Map<String, Object> event) {
        webhooks.receive(event);
        return ResponseEntity.ok().build();
    }
}
