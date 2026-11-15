package mx.velora.api.integration.strava;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import mx.velora.api.activity.ActivityDeletion;
import mx.velora.api.activity.ActivityLookup;
import mx.velora.api.integration.strava.StravaProblems.AthleteAlreadyLinkedException;
import mx.velora.api.integration.strava.StravaProblems.InvalidAuthorizationException;
import mx.velora.api.integration.strava.StravaProblems.MissingScopeException;
import mx.velora.api.integration.strava.StravaProblems.StravaDisabledException;
import mx.velora.api.integration.strava.StravaProblems.StravaUnavailableException;
import mx.velora.api.platform.logging.RedactedException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

/** Conectar, consultar y desconectar la cuenta de Strava del usuario (ADR-030). */
@Service
public class StravaConnections {

    /** Permiso necesario: actividades, incluidas las privadas (son del propio usuario). */
    static final String REQUIRED_SCOPE = "activity:read_all";

    static final String SOURCE_TYPE = "STRAVA";

    /** Margen para renovar el access token antes de que venza. */
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(StravaConnections.class);

    /**
     * @param backfillDone si la carga inicial del historial terminó
     * @param lastSyncAt última ejecución de la sincronización
     * @param lastSyncError {@code AUTH_REVOKED} o {@code STRAVA_UNAVAILABLE} si la última ejecución falló
     */
    public record Status(
            boolean enabled,
            boolean connected,
            @Nullable Long athleteId,
            @Nullable Instant connectedAt,
            long importedActivities,
            boolean backfillDone,
            @Nullable Instant lastSyncAt,
            @Nullable String lastSyncError) {}

    private final StravaProperties properties;
    private final StravaConnectionRepository connections;
    private final StravaAuth auth;
    private final @Nullable TokenCipher cipher;
    private final ActivityLookup activities;
    private final ActivityDeletion deletion;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    StravaConnections(
            StravaProperties properties,
            StravaConnectionRepository connections,
            StravaAuth auth,
            ActivityLookup activities,
            ActivityDeletion deletion,
            Clock clock) {
        this.properties = properties;
        this.connections = connections;
        this.auth = auth;
        this.cipher = properties.enabled() ? new TokenCipher(properties.tokenKey()) : null;
        this.activities = activities;
        this.deletion = deletion;
        this.clock = clock;
    }

    public Status status(UUID userId) {
        if (!properties.enabled()) {
            return new Status(false, false, null, null, 0, false, null, null);
        }
        var row = connections.find(userId);
        var sync = connections.syncState(userId);
        return new Status(
                true,
                row.isPresent(),
                row.map(StravaConnectionRepository.Row::athleteId).orElse(null),
                row.map(StravaConnectionRepository.Row::connectedAt).orElse(null),
                activities.countBySource(userId, SOURCE_TYPE),
                sync.map(StravaConnectionRepository.SyncState::backfillDone).orElse(false),
                sync.map(StravaConnectionRepository.SyncState::lastSyncAt).orElse(null),
                sync.map(StravaConnectionRepository.SyncState::lastSyncError).orElse(null));
    }

    /** URL de autorización de Strava con un estado de un solo uso ligado al usuario. */
    public String authorizationUrl(UUID userId) {
        requireEnabled();
        var state = new byte[32];
        random.nextBytes(state);
        var encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(state);
        connections.saveState(sha256(encoded), userId, clock.instant().plus(properties.stateTtl()));
        return UriComponentsBuilder.fromUri(properties.baseUrl())
                .path("/oauth/authorize")
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("approval_prompt", "auto")
                .queryParam("scope", "read," + REQUIRED_SCOPE)
                .queryParam("state", encoded)
                .encode()
                .toUriString();
    }

    /** Completa la autorización con lo que Strava devolvió en el retorno. */
    @Transactional
    public Status connect(UUID userId, String code, String state, String grantedScope) {
        requireEnabled();
        if (!connections.consumeState(sha256(state), userId, clock.instant())) {
            throw new InvalidAuthorizationException();
        }
        if (!Arrays.asList(grantedScope.split(",")).contains(REQUIRED_SCOPE)) {
            throw new MissingScopeException();
        }
        StravaAuth.Tokens tokens;
        try {
            tokens = auth.exchange(code);
        } catch (StravaAuth.RejectedException e) {
            throw new InvalidAuthorizationException();
        } catch (StravaAuth.UnavailableException e) {
            log.warn("Strava no respondió al conectar", RedactedException.of(e));
            throw new StravaUnavailableException();
        }
        if (tokens.athleteId() == null) {
            throw new StravaUnavailableException();
        }
        var owner = connections.ownerOfAthlete(tokens.athleteId());
        if (owner.isPresent() && !owner.get().equals(userId)) {
            throw new AthleteAlreadyLinkedException();
        }
        var now = clock.instant();
        connections.upsert(
                new StravaConnectionRepository.Row(
                        userId,
                        tokens.athleteId(),
                        grantedScope,
                        cipher().encrypt(userId, tokens.accessToken()),
                        cipher().encrypt(userId, tokens.refreshToken()),
                        tokens.expiresAt(),
                        now),
                now);
        log.info("Cuenta de Strava conectada");
        return status(userId);
    }

    /**
     * Revoca el acceso en Strava (si responde), borra la conexión y, si se pide, todas las actividades importadas de
     * Strava. Idempotente.
     *
     * @return cuántas actividades se borraron
     */
    @Transactional
    public int disconnect(UUID userId, boolean deleteActivities) {
        requireEnabled();
        connections.find(userId).ifPresent(row -> {
            try {
                auth.deauthorize(cipher().decrypt(userId, row.accessToken()));
            } catch (RuntimeException e) {
                // Si Strava no responde o el token ya no sirve, la conexión se borra igual de este lado.
                log.warn("No se pudo revocar el acceso en Strava", RedactedException.of(e));
            }
            connections.delete(userId);
        });
        var deleted = deleteActivities ? deletion.deleteAllFromSource(userId, SOURCE_TYPE) : 0;
        log.info("Cuenta de Strava desconectada; {} actividades de Strava borradas", deleted);
        return deleted;
    }

    /** Access token vigente para llamar a la API de Strava; lo renueva si está por vencer. */
    @Transactional
    public String accessToken(UUID userId) {
        requireEnabled();
        var row = connections.find(userId).orElseThrow(InvalidAuthorizationException::new);
        var now = clock.instant();
        if (row.accessExpiresAt().isAfter(now.plus(REFRESH_MARGIN))) {
            return cipher().decrypt(userId, row.accessToken());
        }
        var tokens = auth.refresh(cipher().decrypt(userId, row.refreshToken()));
        connections.updateTokens(
                userId,
                cipher().encrypt(userId, tokens.accessToken()),
                cipher().encrypt(userId, tokens.refreshToken()),
                tokens.expiresAt(),
                now);
        return tokens.accessToken();
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new StravaDisabledException();
        }
    }

    private TokenCipher cipher() {
        if (cipher == null) {
            throw new StravaDisabledException();
        }
        return cipher;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
