package mx.velora.api.integration.strava;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** OAuth de Strava. Una interfaz para que las pruebas usen un doble sin red. */
public interface StravaAuth {

    /**
     * @param athleteId solo en el intercambio del código; la renovación no lo trae
     */
    record Tokens(
            String accessToken,
            String refreshToken,
            Instant expiresAt,
            @Nullable Long athleteId) {}

    /** Strava rechazó el código o el refresh token (4xx): hay que volver a autorizar. */
    final class RejectedException extends RuntimeException {
        public RejectedException() {
            super("Strava rechazó la credencial");
        }
    }

    /** Strava no respondió o respondió 5xx: se puede reintentar. */
    final class UnavailableException extends RuntimeException {
        public UnavailableException(Throwable cause) {
            super("Strava no está disponible", cause);
        }
    }

    Tokens exchange(String code);

    Tokens refresh(String refreshToken);

    /** Revoca el acceso de la app. */
    void deauthorize(String accessToken);
}
