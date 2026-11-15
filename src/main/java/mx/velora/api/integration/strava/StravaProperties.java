package mx.velora.api.integration.strava;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración del conector de Strava ({@code velora.strava.*}, ADR-030). Apagado por omisión; encendido exige
 * credenciales, clave de cifrado y URL de retorno, o la API no arranca.
 *
 * @param enabled si el conector está disponible
 * @param clientId id de la app registrada en Strava
 * @param clientSecret secreto de la app; solo por variable de entorno
 * @param tokenKey clave AES-256 en Base64 para cifrar los tokens en la base
 * @param redirectUri URL de la web que recibe el retorno de la autorización
 * @param baseUrl raíz de Strava (OAuth y API); configurable para pruebas
 * @param stateTtl vigencia del estado OAuth
 * @param backfillSince fecha desde la que la carga inicial trae actividades; sin ella, todo el historial
 * @param sync ejecución periódica de la sincronización
 * @param webhook suscripción a los eventos de Strava
 */
@ConfigurationProperties("velora.strava")
public record StravaProperties(
        @DefaultValue("false") boolean enabled,
        @Nullable String clientId,
        @Nullable String clientSecret,
        @Nullable String tokenKey,
        @Nullable URI redirectUri,
        @DefaultValue("https://www.strava.com") URI baseUrl,
        @DefaultValue("PT10M") Duration stateTtl,
        @Nullable LocalDate backfillSince,
        @DefaultValue Sync sync,
        @DefaultValue Webhook webhook) {

    /**
     * @param enabled si se registra la suscripción y se procesan los eventos (las pruebas los procesan a mano)
     * @param callbackUrl URL pública de {@code /api/v1/integrations/strava/webhook}; sin ella no hay suscripción
     */
    public record Webhook(
            @DefaultValue("true") boolean enabled, @Nullable URI callbackUrl) {}

    /**
     * @param enabled si corre sola (las pruebas la invocan a mano)
     * @param interval espera entre ejecuciones; coincide con la ventana de 15 min de la cuota de Strava
     * @param perRun actividades nuevas que se procesan como máximo por ejecución
     */
    public record Sync(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT15M") Duration interval,
            @DefaultValue("40") int perRun) {}

    public StravaProperties {
        if (enabled) {
            require(clientId, "client-id");
            require(clientSecret, "client-secret");
            require(tokenKey, "token-key");
            if (redirectUri == null) {
                throw new IllegalStateException("velora.strava.redirect-uri es obligatorio con el conector encendido");
            }
            if (Base64.getDecoder().decode(tokenKey).length != 32) {
                throw new IllegalStateException("velora.strava.token-key debe ser una clave de 32 bytes en Base64");
            }
        }
    }

    private static void require(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("velora.strava." + name + " es obligatorio con el conector encendido");
        }
    }
}
