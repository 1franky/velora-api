package mx.velora.api.importing;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import mx.velora.api.activity.ActivityRegistration;
import mx.velora.api.activity.ActivitySubType;
import mx.velora.api.activity.Metric;
import mx.velora.api.activity.Metric.Source;
import mx.velora.api.activity.NewActivity;
import mx.velora.api.activity.NewActivity.MetricValue;
import mx.velora.api.activity.StreamType;
import mx.velora.api.contract.OpenApiContract;
import mx.velora.api.identity.user.OwnerBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class PossibleDuplicatesIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final AtomicInteger SEED = new AtomicInteger(40);
    private static final Instant START = Instant.parse("2026-11-08T14:00:00Z");

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

    /** Rodada sintética de ~1 h y ~30 km en un recorrido recto que empieza en (lat, lon). */
    private UUID ride(String title, Instant startedAt, double elapsedSeconds, double distanceMeters, double lat) {
        var sha = new byte[32];
        Arrays.fill(sha, (byte) SEED.incrementAndGet());
        var points = 50;
        var latitudes = new Double[points];
        var longitudes = new Double[points];
        for (int i = 0; i < points; i++) {
            latitudes[i] = lat + i * 0.002;
            longitudes[i] = -99.0 + i * 0.002;
        }
        var streams = new EnumMap<StreamType, Double[]>(StreamType.class);
        streams.put(StreamType.LATITUDE, latitudes);
        streams.put(StreamType.LONGITUDE, longitudes);
        return registration.register(new NewActivity(
                session.userId(),
                null,
                null,
                title,
                ActivitySubType.GRAVEL,
                startedAt,
                ZoneId.of("America/Mexico_City"),
                "GPX",
                null,
                List.of(
                        new MetricValue(Metric.ELAPSED_TIME, Source.DERIVED, elapsedSeconds, "derived-metrics/1", null),
                        new MetricValue(Metric.MOVING_TIME, Source.DERIVED, elapsedSeconds, "derived-metrics/1", null),
                        new MetricValue(Metric.DISTANCE, Source.DERIVED, distanceMeters, "derived-metrics/1", null)),
                new NewActivity.Samples(points, new int[points], streams),
                List.of(),
                new NewActivity.OriginalFile(session.userId() + "/" + UUID.randomUUID(), sha, 1, "velora-gpx", "1")));
    }

    private List<String> duplicatesOf(UUID id) throws Exception {
        var body = mvc.perform(session.authorize(get("/api/v1/activities/" + id + "/possible-duplicates")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.items[*].title");
    }

    @Test
    void findsTheSameRideImportedInOtherFormatsButNotOtherRides() throws Exception {
        var fit = ride("FIT", START, 3_600, 30_000, 19.0);
        ride("GPX", START.plusSeconds(20), 3_610, 30_400, 19.0);
        ride("TCX", START.plusSeconds(40), 3_590, 29_800, 19.0);
        ride("Otra salida ese día", START.plusSeconds(90), 3_600, 30_000, 25.0); // otra zona
        ride("Más larga", START, 5_400, 45_000, 19.0); // empieza igual pero dura mucho más
        ride("Al día siguiente", START.plusSeconds(86_400), 3_600, 30_000, 19.0);

        assertThat(duplicatesOf(fit)).containsExactly("GPX", "TCX");
    }

    @Test
    void anActivityWithoutLookalikesHasAnEmptyList() throws Exception {
        var only = ride("Única", START.plusSeconds(7 * 86_400), 3_600, 30_000, 19.0);

        assertThat(duplicatesOf(only)).isEmpty();
    }

    @Test
    void onlyTheOwnerCanAskAndOtherUsersActivitiesAreNeverListed() throws Exception {
        var mine = ride("Mía", START.plusSeconds(14 * 86_400), 3_600, 30_000, 19.0);
        var theirs = ride("Ajena", START.plusSeconds(14 * 86_400 + 10), 3_600, 30_000, 19.0);
        var other = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, 'x')")
                .param("id", other)
                .param("email", other + "@example.com")
                .update();
        jdbc.sql("UPDATE activities SET user_id = :other WHERE id = :id")
                .param("other", other)
                .param("id", theirs)
                .update();

        assertThat(duplicatesOf(mine)).isEmpty();
        mvc.perform(session.authorize(get("/api/v1/activities/" + theirs + "/possible-duplicates")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La actividad no existe."));
        mvc.perform(get("/api/v1/activities/" + mine + "/possible-duplicates")).andExpect(status().isUnauthorized());
    }
}
