package mx.velora.api.integration.strava;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** {@link StravaAuth} contra {@code /oauth/token} y {@code /oauth/deauthorize}. Nunca registra tokens. */
class HttpStravaAuth implements StravaAuth {

    /** Solo los campos que se usan de la respuesta de Strava. */
    record TokenResponse(
            String access_token,
            String refresh_token,
            long expires_at,
            @Nullable Athlete athlete) {
        record Athlete(long id) {}
    }

    private final RestClient http;
    private final String clientId;
    private final String clientSecret;

    HttpStravaAuth(RestClient.Builder builder, StravaProperties properties) {
        this.http = builder.baseUrl(properties.baseUrl().toString()).build();
        this.clientId = properties.clientId();
        this.clientSecret = properties.clientSecret();
    }

    @Override
    public Tokens exchange(String code) {
        return token("authorization_code", "code", code);
    }

    @Override
    public Tokens refresh(String refreshToken) {
        return token("refresh_token", "refresh_token", refreshToken);
    }

    @Override
    public void deauthorize(String accessToken) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("access_token", accessToken);
        call(() -> http.post()
                .uri("/oauth/deauthorize")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity());
    }

    private Tokens token(String grantType, String field, String value) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("grant_type", grantType);
        form.add(field, value);
        var response = call(() -> http.post()
                .uri("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class));
        if (response == null || response.access_token() == null || response.refresh_token() == null) {
            throw new UnavailableException(new IllegalStateException("Respuesta de Strava incompleta"));
        }
        return new Tokens(
                response.access_token(),
                response.refresh_token(),
                Instant.ofEpochSecond(response.expires_at()),
                response.athlete() == null ? null : response.athlete().id());
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                throw new UnavailableException(e);
            }
            throw new RejectedException();
        } catch (RestClientException e) {
            throw new UnavailableException(e);
        }
    }
}
