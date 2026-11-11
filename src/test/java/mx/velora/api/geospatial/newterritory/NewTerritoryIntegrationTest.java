package mx.velora.api.geospatial.newterritory;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class NewTerritoryIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final AtomicInteger SEED = new AtomicInteger(200);
    private static final double STEP = 0.00005; // ~5.56 m hacia el norte
    /** ~40 m al este en la latitud 19. */
    private static final double PARALLEL = 0.00038;

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

    /** Recta hacia el norte desde (19, -99 + {@code lonOffset}) con {@code points} muestras. */
    private UUID ride(int points, double lonOffset, String startedAt) {
        var sha = new byte[32];
        Arrays.fill(sha, (byte) SEED.incrementAndGet());
        var lat = new Double[points];
        var lon = new Double[points];
        for (int i = 0; i < points; i++) {
            lat[i] = 19.0 + i * STEP;
            lon[i] = -99.0 + lonOffset;
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
                Instant.parse(startedAt),
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

    private String newTerritory(UUID id, String extra) throws Exception {
        return mvc.perform(session.authorize(get("/api/v1/geo/new-territory?activityId=" + id + extra)))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static double number(String json, String path) {
        return ((Number) JsonPath.read(json, path)).doubleValue();
    }

    @Test
    void theFirstRideIsAllNewAndALongerRepeatOnlyAddsItsExtension() throws Exception {
        var first = ride(1_000, 0, "2026-11-01T14:00:00Z"); // ~5.55 km
        var longer = ride(2_000, 0, "2026-11-08T14:00:00Z"); // ~11.1 km por el mismo camino

        var body = newTerritory(first, "");
        assertThat((Boolean) JsonPath.read(body, "$.firstActivity")).isTrue();
        assertThat(number(body, "$.newFraction")).isGreaterThan(0.99);

        var started = System.nanoTime();
        body = newTerritory(longer, "");
        var millis = (System.nanoTime() - started) / 1_000_000;
        assertThat((Integer) JsonPath.read(body, "$.comparedActivities")).isEqualTo(1);
        assertThat((Boolean) JsonPath.read(body, "$.firstActivity")).isFalse();
        // La mitad norte es nueva, salvo los ~25 m de tolerancia junto al final de la primera.
        assertThat(number(body, "$.newDistanceMeters")).isBetween(5_450.0, 5_560.0);
        assertThat(number(body, "$.newFraction")).isBetween(0.48, 0.51);
        assertThat((String) JsonPath.read(body, "$.confidence")).isEqualTo("HIGH");
        List<Object> stretches = JsonPath.read(body, "$.geometry.coordinates");
        assertThat(stretches).hasSize(1);
        assertThat(millis).as("11 km contra una rodada previa").isLessThan(3_000);
    }

    @Test
    void aParallelRoadIsNewButWithLowConfidenceAndKnownWithAWiderTolerance() throws Exception {
        ride(1_000, 0, "2026-11-01T14:00:00Z");
        var parallel = ride(1_000, PARALLEL, "2026-11-08T14:00:00Z");

        var body = newTerritory(parallel, "");
        assertThat(number(body, "$.newFraction")).isGreaterThan(0.95);
        assertThat((String) JsonPath.read(body, "$.confidence")).isEqualTo("LOW");

        body = newTerritory(parallel, "&toleranceMeters=50");
        assertThat(number(body, "$.newDistanceMeters")).isZero();
        assertThat((Object) JsonPath.read(body, "$.geometry")).isNull();
    }

    @Test
    void analyzesTheTrackTrimmedByPrivacy() throws Exception {
        var id = ride(2_000, 0, "2026-11-08T14:00:00Z");
        var full = number(newTerritory(id, ""), "$.analyzedDistanceMeters");

        mvc.perform(session.authorize(patch("/api/v1/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackPrivacyMeters\":500}")))
                .andExpect(status().isOk());
        var body = newTerritory(id, "");
        assertThat((Boolean) JsonPath.read(body, "$.trimmed")).isTrue();
        // Entre 700 y 1 300 m menos (350–650 m por extremo).
        assertThat(full - number(body, "$.analyzedDistanceMeters")).isBetween(690.0, 1_310.0);
    }

    @Test
    void rejectsUnknownActivitiesAndToleranceOutOfRange() throws Exception {
        var id = ride(200, 0, "2026-11-08T14:00:00Z");
        mvc.perform(session.authorize(get("/api/v1/geo/new-territory?activityId=" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La actividad no existe o no tiene recorrido con GPS."));
        mvc.perform(session.authorize(get("/api/v1/geo/new-territory?activityId=" + id + "&toleranceMeters=5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("La tolerancia debe estar entre 10 y 100 metros."));
    }
}
