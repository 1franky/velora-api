package mx.velora.api.identity.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Limita intentos de login fallidos por clave (correo y dirección IP), en memoria (ADR-010).
 *
 * <p>Tras {@code freeFailures} fallos dentro de la ventana, cada fallo adicional impone una espera que se duplica,
 * desde {@code baseDelay} hasta {@code maxDelay}. Mientras haya espera, ni siquiera se verifica la contraseña. Un
 * login exitoso olvida los fallos de sus claves. Válido para una sola instancia de la API.
 */
@Component
public class LoginThrottle {

    /** Límite de entradas antes de purgar las caducadas; acota memoria ante muchas claves distintas. */
    static final int PURGE_THRESHOLD = 10_000;

    private record State(
            int failures, Instant windowStart, @Nullable Instant lockedUntil) {}

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final AuthProperties.Throttle policy;
    private final Clock clock;

    LoginThrottle(AuthProperties properties, Clock clock) {
        this.policy = properties.throttle();
        this.clock = clock;
    }

    /** Espera restante más larga entre las claves dadas, si alguna está bloqueada. */
    public Optional<Duration> retryAfter(String... keys) {
        var now = clock.instant();
        Duration longest = null;
        for (var key : keys) {
            var state = states.get(key);
            if (state != null
                    && state.lockedUntil() != null
                    && state.lockedUntil().isAfter(now)) {
                var remaining = Duration.between(now, state.lockedUntil());
                longest = longest == null || remaining.compareTo(longest) > 0 ? remaining : longest;
            }
        }
        return Optional.ofNullable(longest);
    }

    public void recordFailure(String... keys) {
        var now = clock.instant();
        if (states.size() > PURGE_THRESHOLD) {
            states.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
        }
        for (var key : keys) {
            states.compute(key, (k, state) -> next(state, now));
        }
    }

    public void recordSuccess(String... keys) {
        for (var key : keys) {
            states.remove(key);
        }
    }

    private State next(@Nullable State state, Instant now) {
        var fresh = state == null || isExpired(state, now);
        var failures = fresh ? 1 : state.failures() + 1;
        var windowStart = fresh ? now : state.windowStart();
        return new State(failures, windowStart, lockUntil(failures, now));
    }

    private @Nullable Instant lockUntil(int failures, Instant now) {
        var excess = failures - policy.freeFailures();
        if (excess < 0) {
            return null;
        }
        // Evita desbordar el desplazamiento: a partir de 2^20 la espera ya supera cualquier máximo razonable.
        var multiplier = 1L << Math.min(excess, 20);
        var delay = policy.baseDelay().multipliedBy(multiplier);
        return now.plus(delay.compareTo(policy.maxDelay()) > 0 ? policy.maxDelay() : delay);
    }

    private boolean isExpired(State state, Instant now) {
        var locked = state.lockedUntil() != null && state.lockedUntil().isAfter(now);
        return !locked && state.windowStart().plus(policy.window()).isBefore(now);
    }
}
