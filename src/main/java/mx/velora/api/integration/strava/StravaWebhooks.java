package mx.velora.api.integration.strava;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import mx.velora.api.activity.ActivityDeletion;
import mx.velora.api.activity.ActivityEditing;
import mx.velora.api.activity.ActivityLookup;
import mx.velora.api.integration.strava.StravaProblems.StravaDisabledException;
import mx.velora.api.platform.logging.RedactedException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Eventos de Strava (ADR-030). Se guardan al llegar y se procesan después:
 *
 * <ul>
 *   <li>actividad creada → sincronización del usuario (la trae la incremental);
 *   <li>actividad renombrada → mismo título en Velora;
 *   <li>actividad borrada → se borra en Velora y no se vuelve a importar (compromiso: menos de 48 h);
 *   <li>atleta que revoca el acceso → se olvida la conexión, lo importado se conserva.
 * </ul>
 *
 * Strava no firma los eventos: solo se aceptan los de la suscripción propia y de atletas conectados, y el contenido
 * del evento se trata como una pista (lo que se importa siempre se pide a la API con el token del usuario).
 */
@Service
public class StravaWebhooks {

    /** Reintentos de un borrado cuya importación todavía no termina, antes de darlo por resuelto. */
    static final int DELETE_RETRIES = 20;

    static final int MAX_TITLE = 200;

    private static final Logger log = LoggerFactory.getLogger(StravaWebhooks.class);

    private final StravaProperties properties;
    private final StravaWebhookRepository events;
    private final StravaConnectionRepository connections;
    private final StravaConnections connectionService;
    private final StravaSync sync;
    private final ActivityLookup activities;
    private final ActivityEditing editing;
    private final ActivityDeletion deletion;
    private final Clock clock;
    private final AtomicReference<@Nullable Long> subscriptionId = new AtomicReference<>();

    StravaWebhooks(
            StravaProperties properties,
            StravaWebhookRepository events,
            StravaConnectionRepository connections,
            StravaConnections connectionService,
            StravaSync sync,
            ActivityLookup activities,
            ActivityEditing editing,
            ActivityDeletion deletion,
            Clock clock) {
        this.properties = properties;
        this.events = events;
        this.connections = connections;
        this.connectionService = connectionService;
        this.sync = sync;
        this.activities = activities;
        this.editing = editing;
        this.deletion = deletion;
        this.clock = clock;
    }

    /** Token que Strava devuelve al validar la suscripción; derivado del secreto de la app, nunca se guarda. */
    String verifyToken() {
        requireEnabled();
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(("velora-strava-webhook:" + properties.clientSecret()).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(digest)
                    .substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return el {@code hub.challenge} si el token coincide */
    @Nullable String validate(@Nullable String mode, @Nullable String token, @Nullable String challenge) {
        requireEnabled();
        if (!"subscribe".equals(mode) || challenge == null || token == null) {
            return null;
        }
        return MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8), verifyToken().getBytes(StandardCharsets.UTF_8))
                ? challenge
                : null;
    }

    void useSubscription(long id) {
        subscriptionId.set(id);
    }

    @Nullable Long subscription() {
        return subscriptionId.get();
    }

    /**
     * Guarda un evento si es de la suscripción propia y de un atleta conectado. Siempre se responde 200 a Strava: un
     * evento ignorado no debe provocar reintentos.
     *
     * @return si se guardó
     */
    boolean receive(Map<String, Object> body) {
        requireEnabled();
        var expected = subscriptionId.get();
        var subscription = number(body.get("subscription_id"));
        var owner = number(body.get("owner_id"));
        var objectId = number(body.get("object_id"));
        var objectType = text(body.get("object_type"));
        var aspect = text(body.get("aspect_type"));
        if (expected == null
                || !expected.equals(subscription)
                || owner == null
                || objectId == null
                || objectType == null
                || aspect == null) {
            return false;
        }
        if (connections.ownerOfAthlete(owner).isEmpty()) {
            return false;
        }
        String title = null;
        Boolean authorized = null;
        if (body.get("updates") instanceof Map<?, ?> updates) {
            title = text(updates.get("title"));
            var auth = text(updates.get("authorized"));
            authorized = auth == null ? null : Boolean.valueOf(auth);
        }
        events.insert(owner, objectType, objectId, aspect, truncate(title), authorized, clock.instant());
        return true;
    }

    /** Procesa los eventos pendientes. Las actividades nuevas se agrupan: una sincronización por usuario. */
    public void processPending() {
        if (!properties.enabled()) {
            return;
        }
        var toSync = new LinkedHashSet<UUID>();
        for (var event : events.pending(100)) {
            try {
                var user = connections.ownerOfAthlete(event.ownerId()).orElse(null);
                if (user == null) {
                    events.processed(event.id(), clock.instant());
                    continue;
                }
                if (handle(event, user, toSync)) {
                    events.processed(event.id(), clock.instant());
                } else {
                    events.retryLater(event.id());
                }
            } catch (RuntimeException e) {
                log.warn("No se pudo procesar un evento de Strava", RedactedException.of(e));
                events.retryLater(event.id());
            }
        }
        for (var user : toSync) {
            sync.syncUser(user);
        }
    }

    /** @return si el evento quedó resuelto */
    private boolean handle(StravaWebhookRepository.Event event, UUID user, java.util.Set<UUID> toSync) {
        var now = clock.instant();
        if ("athlete".equals(event.objectType())) {
            if (Boolean.FALSE.equals(event.authorized())) {
                connectionService.forget(user);
            }
            return true;
        }
        if (!"activity".equals(event.objectType())) {
            return true;
        }
        var key = "strava:" + event.objectId();
        switch (event.aspectType()) {
            case "create" -> toSync.add(user);
            case "update" -> {
                if (event.title() != null && !event.title().isBlank()) {
                    for (var id : activities.withSourceKey(user, key)) {
                        editing.update(
                                user,
                                id,
                                new ActivityEditing.Changes(event.title().strip(), null, null),
                                now);
                    }
                }
            }
            case "delete" -> {
                var ids = activities.withSourceKey(user, key);
                for (var id : ids) {
                    deletion.delete(user, id);
                }
                var submitted = connections.wasSubmitted(user, event.objectId());
                connections.markDeleted(user, event.objectId(), now);
                // Se envió a importar pero aún no se registra: se reintenta hasta que aparezca y se borre.
                if (ids.isEmpty() && submitted && event.attempts() < DELETE_RETRIES) {
                    return false;
                }
                log.info("Actividad borrada en Strava; {} borradas en Velora", ids.size());
            }
            default -> {
                // Aspecto desconocido: nada que hacer.
            }
        }
        return true;
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new StravaDisabledException();
        }
    }

    private static @Nullable Long number(@Nullable Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : Objects.toString(value);
    }

    private static @Nullable String truncate(@Nullable String title) {
        return title == null || title.length() <= MAX_TITLE ? title : title.substring(0, MAX_TITLE);
    }
}
