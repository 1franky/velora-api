package mx.velora.api.identity.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purga diaria de tokens expirados y sesiones vacías.
 *
 * <p>Un token expirado ya no autentica ni rota, así que conservarlo no aporta nada. Los refresh tokens rotados
 * pero vigentes se conservan: son los que permiten detectar una reutilización y revocar la sesión. Una sesión sin
 * tokens ya no puede usarse; el margen evita borrar una recién creada cuyo primer token aún no se inserta.
 */
@Component
class AuthCleanup {

    private static final Logger log = LoggerFactory.getLogger(AuthCleanup.class);

    /** Margen antes de borrar, para no competir con operaciones en curso. */
    static final Duration GRACE = Duration.ofDays(1);

    record Result(int tokens, int sessions) {}

    private final TokenRepository tokens;
    private final Clock clock;

    AuthCleanup(TokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${velora.auth.cleanup.cron:0 15 4 * * *}",
            zone = "${velora.auth.cleanup.zone:America/Mexico_City}")
    void scheduled() {
        try {
            purge(clock.instant());
        } catch (RuntimeException e) {
            log.warn("La limpieza de tokens expirados falló; se reintentará en la siguiente ejecución", e);
        }
    }

    Result purge(Instant now) {
        var cutoff = now.minus(GRACE);
        var result =
                new Result(tokens.deleteTokensExpiredBefore(cutoff), tokens.deleteEmptySessionsCreatedBefore(cutoff));
        log.info("Limpieza de autenticación: {} tokens y {} sesiones borrados", result.tokens(), result.sessions());
        return result;
    }
}
