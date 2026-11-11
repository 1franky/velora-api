package mx.velora.api.activity;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import mx.velora.api.IntegrationTest;
import mx.velora.api.TestSessions;
import mx.velora.api.activity.Metric.Source;
import mx.velora.api.activity.NewActivity.MetricValue;
import mx.velora.api.contract.OpenApiContract;
import mx.velora.api.identity.user.OwnerBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class TrackPrivacyIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final AtomicInteger SEED = new AtomicInteger(90);
    private static final double START_LAT = 19.0;
    private static final double STEP = 0.00005; // ~5.56 m

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    @Autowired
    ActivityRegistration registration;

    private TestSessions.Session session;

    @BeforeEach
    void login() throws Exception {
        session = TestSessions.freshOwner(mvc, jdbc, bootstrap);
    }

    /** Recta hacia el norte de {@code points} muestras (~5.56 m entre ellas). */
    private UUID ride(int points) {
        var sha = new byte[32];
        Arrays.fill(sha, (byte) SEED.incrementAndGet());
        var lat = new Double[points];
        var lon = new Double[points];
        for (int i = 0; i < points; i++) {
            lat[i] = START_LAT + i * STEP;
            lon[i] = -99.0;
        }
        var streams = new EnumMap<StreamType, Double[]>(StreamType.class);
        streams.put(StreamType.LATITUDE, lat);
        streams.put(StreamType.LONGITUDE, lon);
        return registration.register(new NewActivity(
                session.userId(),
                null,
                null,
                "Recta",
                ActivitySubType.ROAD,
                Instant.parse("2026-11-08T14:00:00Z"),
                ZoneId.of("America/Mexico_City"),
                "GPX",
                null,
                List.of(
                        new MetricValue(Metric.ELAPSED_TIME, Source.DERIVED, points, "derived-metrics/1", null),
                        new MetricValue(Metric.MOVING_TIME, Source.DERIVED, points, "derived-metrics/1", null)),
                new NewActivity.Samples(points, new int[points], streams),
                List.of(),
                new NewActivity.OriginalFile(session.userId() + "/" + UUID.randomUUID(), sha, 1, "velora-gpx", "1")));
    }

    private void privacy(int meters) throws Exception {
        mvc.perform(session.authorize(patch("/api/v1/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackPrivacyMeters\":" + meters + "}")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.trackPrivacyMeters").value(meters));
    }

    private String track(UUID id) throws Exception {
        return mvc.perform(session.authorize(get("/api/v1/activities/" + id + "/track?zoom=22")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void isOffByDefaultAndTheWholeTrackIsServed() throws Exception {
        mvc.perform(session.authorize(get("/api/v1/me")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.preferences.trackPrivacyMeters").value(0));
        var id = ride(2_000);

        var body = track(id);
        assertThat((Boolean) JsonPath.read(body, "$.properties.trimmed")).isFalse();
        assertThat((Double) JsonPath.read(body, "$.bbox[1]"))
                .isEqualTo(START_LAT, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void hidesStartAndEndInTheTrackItsBboxAndTheStreams() throws Exception {
        var id = ride(2_000); // ~11 km
        privacy(300);

        var body = track(id);
        assertThat((Boolean) JsonPath.read(body, "$.properties.trimmed")).isTrue();
        double south = JsonPath.read(body, "$.bbox[1]");
        double north = JsonPath.read(body, "$.bbox[3]");
        // Entre 210 y 390 m (±30 %) ocultos en cada extremo; el recuadro no delata el punto de salida.
        assertThat((south - START_LAT) * 111_195).isBetween(205.0, 395.0);
        assertThat((START_LAT + 1_999 * STEP - north) * 111_195).isBetween(205.0, 395.0);
        List<Double> firstPoint = JsonPath.read(body, "$.geometry.coordinates[0][0]");
        assertThat(firstPoint.get(1)).isEqualTo(south, org.assertj.core.data.Offset.offset(1e-6));

        var streams = mvc.perform(session.authorize(get("/api/v1/activities/" + id + "/streams?types=LATITUDE")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Double> lat = JsonPath.read(streams, "$.streams.LATITUDE");
        assertThat(lat.get(0)).isNull();
        assertThat(lat.get(1_000)).isNotNull();
        assertThat(lat.get(1_999)).isNull();
        java.util.Map<String, Object> returned = JsonPath.read(streams, "$.streams");
        assertThat(returned.keySet()).as("solo lo pedido").containsExactly("LATITUDE");
        var firstVisible =
                lat.indexOf(lat.stream().filter(v -> v != null).findFirst().orElseThrow());
        assertThat(lat.get(firstVisible)).isGreaterThanOrEqualTo(south - 0.0001);
    }

    @Test
    void aRideShorterThanWhatIsHiddenHasNoVisibleTrack() throws Exception {
        var id = ride(200); // ~1.1 km
        privacy(1000);

        mvc.perform(session.authorize(get("/api/v1/activities/" + id + "/track")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.startsWith("El recorrido completo queda oculto")));
    }

    @Test
    void rejectsValuesOutsideTheAllowedSteps() throws Exception {
        mvc.perform(session.authorize(patch("/api/v1/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackPrivacyMeters\":250}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Elige 0 (desactivada), 200, 300, 500 o 1000 metros."));
    }
}
