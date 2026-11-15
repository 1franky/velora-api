package mx.velora.api.integration.strava;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpStravaAuthTest {

    private MockRestServiceServer server;
    private HttpStravaAuth auth;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        auth = new HttpStravaAuth(
                builder,
                new StravaProperties(
                        true,
                        "123",
                        "secreto",
                        "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                        URI.create("https://velora.local/retorno"),
                        URI.create("https://strava.test"),
                        Duration.ofMinutes(10),
                        null,
                        new StravaProperties.Sync(true, Duration.ofMinutes(15), 40),
                        new StravaProperties.Webhook(false, null)));
    }

    @Test
    void exchangesTheCodeAndReadsTokensExpiryAndAthlete() {
        server.expect(requestTo("https://strava.test/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content()
                        .formDataContains(java.util.Map.of(
                                "client_id", "123",
                                "client_secret", "secreto",
                                "grant_type", "authorization_code",
                                "code", "el-codigo")))
                .andRespond(withSuccess("""
                        {"token_type":"Bearer","expires_at":1800000000,"expires_in":21600,
                         "refresh_token":"r1","access_token":"a1","athlete":{"id":42,"firstname":"X"}}
                        """, MediaType.APPLICATION_JSON));

        var tokens = auth.exchange("el-codigo");

        assertThat(tokens.accessToken()).isEqualTo("a1");
        assertThat(tokens.refreshToken()).isEqualTo("r1");
        assertThat(tokens.expiresAt()).isEqualTo(Instant.ofEpochSecond(1_800_000_000L));
        assertThat(tokens.athleteId()).isEqualTo(42L);
        server.verify();
    }

    @Test
    void refreshUsesTheRefreshGrant() {
        server.expect(requestTo("https://strava.test/oauth/token"))
                .andExpect(content()
                        .formDataContains(java.util.Map.of("grant_type", "refresh_token", "refresh_token", "r1")))
                .andRespond(withSuccess(
                        "{\"expires_at\":1800000000,\"refresh_token\":\"r2\",\"access_token\":\"a2\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(auth.refresh("r1").athleteId()).isNull();
    }

    @Test
    void clientErrorsAreRejectionsButRateLimitsAndServerErrorsAreTransient() {
        server.expect(requestTo("https://strava.test/oauth/token")).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> auth.exchange("x")).isInstanceOf(StravaAuth.RejectedException.class);

        server.reset();
        server.expect(requestTo("https://strava.test/oauth/token"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> auth.exchange("x")).isInstanceOf(StravaAuth.UnavailableException.class);

        server.reset();
        server.expect(requestTo("https://strava.test/oauth/token")).andRespond(withServerError());
        assertThatThrownBy(() -> auth.exchange("x")).isInstanceOf(StravaAuth.UnavailableException.class);
    }

    @Test
    void deauthorizeSendsTheAccessToken() {
        server.expect(requestTo("https://strava.test/oauth/deauthorize"))
                .andExpect(content().formDataContains(java.util.Map.of("access_token", "a1")))
                .andRespond(withSuccess());

        auth.deauthorize("a1");
        server.verify();
    }
}
