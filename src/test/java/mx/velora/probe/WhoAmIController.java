package mx.velora.probe;

import java.util.Map;
import mx.velora.api.identity.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint protegido solo para pruebas. Vive fuera de {@code mx.velora.api} para que el escaneo de componentes no lo
 * registre; las pruebas lo importan explícitamente.
 */
@RestController
public class WhoAmIController {

    @GetMapping("/api/v1/test/whoami")
    Map<String, String> whoami(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("userId", user.userId().toString());
    }
}
