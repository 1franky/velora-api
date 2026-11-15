package mx.velora.api.integration.strava;

import java.net.URI;
import java.util.List;

/** Suscripción de la app a los webhooks de Strava ({@code /push_subscriptions}). Strava admite una por app. */
public interface StravaSubscriptions {

    record Subscription(long id, URI callbackUrl) {}

    List<Subscription> list();

    /** Strava valida la URL en el momento con un {@code GET} al callback, que debe devolver el {@code hub.challenge}. */
    long create(URI callbackUrl, String verifyToken);

    void delete(long id);
}
