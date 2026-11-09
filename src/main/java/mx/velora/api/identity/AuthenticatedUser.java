package mx.velora.api.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Usuario autenticado de la solicitud actual. Es lo único que otros módulos necesitan de {@code identity}: los
 * controladores lo reciben con {@code @AuthenticationPrincipal AuthenticatedUser user} y filtran por {@link #userId()}.
 */
public record AuthenticatedUser(UUID userId) {

    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId");
    }
}
