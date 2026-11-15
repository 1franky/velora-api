package mx.velora.api.integration.strava;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpStravaApiTest {

    private static final Instant NOW = Instant.parse("2026-11-14T18:07:00Z");

    private MockRestServiceServer server;
    private HttpStravaApi api;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(false).build();
        api = new HttpStravaApi(
                builder,
                new StravaProperties(
                        true,
                        "1",
                        "s",
                        "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                        URI.create("https://velora.local/retorno"),
                        URI.create("https://strava.test"),
                        Duration.ofMinutes(10),
                        null,
                        new StravaProperties.Sync(true, Duration.ofMinutes(15), 40),
                        new StravaProperties.Webhook(false, null)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HttpHeaders usage(String usage) {
        var headers = new HttpHeaders();
        headers.add("X-ReadRateLimit-Limit", "100,1000");
        headers.add("X-ReadRateLimit-Usage", usage);
        return headers;
    }

    @Test
    void listsActivitiesWithTheirSummary() {
        server.expect(requestTo("https://strava.test/api/v3/athlete/activities?page=1&per_page=50&before=1763000000"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer t"))
                .andRespond(withSuccess("""
                                [{"id":11,"name":"Matutina","sport_type":"GravelRide","start_date":"2026-11-01T13:00:00Z",
                                  "trainer":false,"manual":false,"elapsed_time":3600,"moving_time":3400,"distance":30500.5,
                                  "total_elevation_gain":420,"average_heartrate":141.2,"kudos_count":3}]
                                """, MediaType.APPLICATION_JSON).headers(usage("10,100")));

        var list = api.activities("t", Instant.ofEpochSecond(1_763_000_000L), null, 1, 50);

        assertThat(list).hasSize(1);
        var summary = list.getFirst().summary();
        assertThat(summary.id()).isEqualTo(11);
        assertThat(summary.sportType()).isEqualTo("GravelRide");
        assertThat(summary.startDate()).isEqualTo(Instant.parse("2026-11-01T13:00:00Z"));
        assertThat(summary.distance()).isEqualTo(30500.5);
        assertThat(summary.averageHeartrate()).isEqualTo(141.2);
        assertThat(list.getFirst().manual()).isFalse();
    }

    @Test
    void readsStreamsByType() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://strava.test/api/v3/activities/11/streams")))
                .andRespond(withSuccess("""
                        {"time":{"data":[0,1],"series_type":"time"},"latlng":{"data":[[19.1,-99.1],[19.2,-99.2]]},
                         "watts":{"data":[200,null]},"moving":{"data":[true,true]}}
                        """, MediaType.APPLICATION_JSON));

        var streams = api.streams("t", 11);

        assertThat(streams.time()).containsExactly(0, 1);
        assertThat(streams.latlng()).hasSize(2);
        assertThat(streams.watts()).containsExactly(200.0, null);
        assertThat(streams.heartrate()).isNull();
    }

    @Test
    void stopsBeforeExhaustingTheQuotaUntilTheNextWindow() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://strava.test/api/v3/athlete/activities")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON).headers(usage("96,500")));

        assertThat(api.activities("t", null, null, 1, 50)).isEmpty();
        // Sin salir a la red: el servidor simulado no espera otra solicitud.
        assertThatThrownBy(() -> api.activities("t", null, null, 1, 50))
                .isInstanceOf(StravaApi.RateLimitedException.class);
        server.verify();
    }

    @Test
    void windowsStartEveryQuarterHour() {
        assertThat(HttpStravaApi.nextQuarterHour(NOW)).isEqualTo(Instant.parse("2026-11-14T18:15:00Z"));
        assertThat(HttpStravaApi.nextQuarterHour(Instant.parse("2026-11-14T18:59:59Z")))
                .isEqualTo(Instant.parse("2026-11-14T19:00:00Z"));
        assertThat(HttpStravaApi.nearLimit(usage("10,985"))).isEqualTo(HttpStravaApi.Window.DAY);
        assertThat(HttpStravaApi.nearLimit(new HttpHeaders())).isEqualTo(HttpStravaApi.Window.NONE);
    }

    @Test
    void translatesErrors() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://strava.test/api/v3/activities/1/streams")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> api.streams("t", 1)).isInstanceOf(StravaApi.NotFoundException.class);

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://strava.test/api/v3/activities/1/streams")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> api.streams("t", 1)).isInstanceOf(StravaAuth.RejectedException.class);

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://strava.test/api/v3/activities/1/streams")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> api.streams("t", 1)).isInstanceOf(StravaApi.RateLimitedException.class);
        assertThatThrownBy(() -> api.streams("t", 1))
                .as("bloqueado hasta la siguiente ventana")
                .isInstanceOf(StravaApi.RateLimitedException.class);
    }
}
