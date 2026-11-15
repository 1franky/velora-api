package mx.velora.api.integration.strava;

import mx.velora.api.platform.logging.RedactedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deja la suscripción de webhooks apuntando al callback configurado: la reutiliza si ya existe, la reemplaza si
 * apunta a otra URL y la crea si falta. Strava valida el callback en el momento, así que corre con la API ya
 * sirviendo.
 */
class StravaWebhookRegistrar {

    private static final Logger log = LoggerFactory.getLogger(StravaWebhookRegistrar.class);

    private final StravaProperties properties;
    private final StravaSubscriptions subscriptions;
    private final StravaWebhooks webhooks;

    StravaWebhookRegistrar(StravaProperties properties, StravaSubscriptions subscriptions, StravaWebhooks webhooks) {
        this.properties = properties;
        this.subscriptions = subscriptions;
        this.webhooks = webhooks;
    }

    void register() {
        var callback = properties.webhook().callbackUrl();
        if (callback == null) {
            log.warn(
                    "Sin velora.strava.webhook.callback-url: no hay webhooks; los cambios llegan solo por sincronización");
            return;
        }
        try {
            for (var existing : subscriptions.list()) {
                if (existing.callbackUrl().equals(callback)) {
                    webhooks.useSubscription(existing.id());
                    log.info("Suscripción a webhooks de Strava vigente");
                    return;
                }
                subscriptions.delete(existing.id());
                log.info("Suscripción a webhooks de Strava con otra URL; se reemplaza");
            }
            webhooks.useSubscription(subscriptions.create(callback, webhooks.verifyToken()));
            log.info("Suscripción a webhooks de Strava creada");
        } catch (RuntimeException e) {
            log.warn(
                    "No se pudo registrar la suscripción a webhooks de Strava; se reintenta al reiniciar",
                    RedactedException.of(e));
        }
    }
}
