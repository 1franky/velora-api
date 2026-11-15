package mx.velora.api.integration.strava;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** {@link StravaSubscriptions} contra {@code /api/v3/push_subscriptions}, autenticado con las credenciales de la app. */
class HttpStravaSubscriptions implements StravaSubscriptions {

    record SubscriptionResponse(long id, String callback_url) {}

    record Created(long id) {}

    private final RestClient http;
    private final String clientId;
    private final String clientSecret;

    HttpStravaSubscriptions(RestClient.Builder builder, StravaProperties properties) {
        this.http = builder.baseUrl(properties.baseUrl() + "/api/v3/push_subscriptions")
                .build();
        this.clientId = properties.clientId();
        this.clientSecret = properties.clientSecret();
    }

    @Override
    public List<Subscription> list() {
        var body = http.get()
                .uri(uri -> uri.queryParam("client_id", clientId)
                        .queryParam("client_secret", clientSecret)
                        .build())
                .retrieve()
                .body(SubscriptionResponse[].class);
        return body == null
                ? List.of()
                : Arrays.stream(body)
                        .map(s -> new Subscription(s.id(), URI.create(s.callback_url())))
                        .toList();
    }

    @Override
    public long create(URI callbackUrl, String verifyToken) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("callback_url", callbackUrl.toString());
        form.add("verify_token", verifyToken);
        var created = http.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Created.class);
        if (created == null) {
            throw new IllegalStateException("Strava no devolvió la suscripción");
        }
        return created.id();
    }

    @Override
    public void delete(long id) {
        http.delete()
                .uri(uri -> uri.path("/{id}")
                        .queryParam("client_id", clientId)
                        .queryParam("client_secret", clientSecret)
                        .build(id))
                .retrieve()
                .toBodilessEntity();
    }
}
