package mx.velora.api.identity.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import mx.velora.api.identity.auth.AuthProblems.InvalidCredentialsException;
import mx.velora.api.identity.auth.AuthProblems.InvalidRefreshTokenException;
import mx.velora.api.identity.auth.AuthProblems.TooManyLoginAttemptsException;
import mx.velora.api.identity.auth.TokenRepository.Kind;
import mx.velora.api.identity.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Login, rotación de refresh tokens y logout (ADR-010).
 *
 * <p>Nunca registra contraseñas ni tokens; los logs solo incluyen identificadores de sesión o usuario.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Par de tokens emitido. Los valores en claro solo existen en la respuesta HTTP. */
    public record TokenPair(
            String accessToken, long accessTokenExpiresIn, String refreshToken, long refreshTokenExpiresIn) {

        @Override
        public String toString() {
            return "TokenPair[redactado]";
        }
    }

    private final UserRepository users;
    private final TokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottle throttle;
    private final AuthProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String timingDecoyHash;

    AuthService(
            UserRepository users,
            TokenRepository tokens,
            PasswordEncoder passwordEncoder,
            LoginThrottle throttle,
            AuthProperties properties,
            TransactionTemplate transactions,
            Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
        // Hash señuelo: verificar contra él cuando el correo no existe iguala el tiempo de respuesta.
        this.timingDecoyHash = passwordEncoder.encode(TokenCodec.generate());
    }

    public TokenPair login(String email, String password, String clientAddress) {
        var keys = new String[] {"email:" + email.strip().toLowerCase(Locale.ROOT), "ip:" + clientAddress};
        throttle.retryAfter(keys).ifPresent(wait -> {
            throw new TooManyLoginAttemptsException(wait);
        });

        var credentials = users.findCredentialsByEmail(email.strip());
        var hash = credentials.map(UserRepository.Credentials::passwordHash).orElse(timingDecoyHash);
        var matches = passwordEncoder.matches(password, hash);
        if (credentials.isEmpty() || !matches) {
            throttle.recordFailure(keys);
            throw new InvalidCredentialsException();
        }
        throttle.recordSuccess(keys);
        var userId = credentials.get().userId();
        return transactions.execute(status -> {
            var now = clock.instant();
            return issue(tokens.createSession(userId, now), now);
        });
    }

    /**
     * Rota un refresh token. Si ya fue usado, revoca la sesión completa (posible robo) y responde 401; la revocación
     * se confirma aunque la solicitud falle.
     */
    public TokenPair refresh(String refreshToken) {
        var result = transactions.execute(status -> rotate(TokenCodec.hash(refreshToken), clock.instant()));
        if (result == null) {
            throw new InvalidRefreshTokenException();
        }
        return result;
    }

    /** Idempotente: revoca la sesión del refresh token si existe. */
    public void logout(String refreshToken) {
        transactions.executeWithoutResult(status -> tokens.findSessionOfRefreshToken(TokenCodec.hash(refreshToken))
                .ifPresent(session -> tokens.revokeSession(session, "LOGOUT", clock.instant())));
    }

    /** Devuelve {@code null} si el token no puede rotarse. */
    private TokenPair rotate(byte[] hash, Instant now) {
        var state = tokens.lockRefreshToken(hash).orElse(null);
        if (state == null || state.sessionRevoked() || !state.expiresAt().isAfter(now)) {
            return null;
        }
        if (state.usedAt() != null) {
            tokens.revokeSession(state.sessionId(), "REFRESH_REUSE", now);
            log.warn(
                    "Reutilización de refresh token detectada; sesión {} del usuario {} revocada",
                    state.sessionId(),
                    state.userId());
            return null;
        }
        tokens.markUsed(hash, now);
        return issue(state.sessionId(), now);
    }

    private TokenPair issue(UUID sessionId, Instant now) {
        var access = TokenCodec.generate();
        var refresh = TokenCodec.generate();
        var accessTtl = properties.accessTokenTtl();
        var refreshTtl = properties.refreshTokenTtl();
        tokens.insertToken(TokenCodec.hash(access), sessionId, Kind.ACCESS, now, now.plus(accessTtl));
        tokens.insertToken(TokenCodec.hash(refresh), sessionId, Kind.REFRESH, now, now.plus(refreshTtl));
        return new TokenPair(access, accessTtl.toSeconds(), refresh, refreshTtl.toSeconds());
    }
}
