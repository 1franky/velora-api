package mx.velora.api.activity;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ClimbsIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final AtomicInteger SEED = new AtomicInteger(250);

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

    private UUID ride(EnumMap<StreamType, Double[]> streams, int samples) {
        var sha = new byte[32];
        Arrays.fill(sha, (byte) SEED.incrementAndGet());
        return registration.register(new NewActivity(
                session.userId(),
                null,
                null,
                "Puerto",
                ActivitySubType.ROAD,
                Instant.parse("2026-11-08T14:00:00Z"),
                ZoneId.of("America/Mexico_City"),
                "FIT",
                null,
                List.of(
                        new MetricValue(Metric.ELAPSED_TIME, Source.DERIVED, samples, "derived-metrics/1", null),
                        new MetricValue(Metric.MOVING_TIME, Source.DERIVED, samples, "derived-metrics/1", null)),
                new NewActivity.Samples(samples, new int[samples], streams),
                List.of(),
                new NewActivity.OriginalFile(session.userId() + "/" + UUID.randomUUID(), sha, 1, "garmin-fit", "1")));
    }

    @Test
    void findsTheClimbFromAltitudeAndDistance() throws Exception {
        // 1 km llano y 2 km al 6 %, una muestra cada 5 m.
        var n = 601;
        var distance = new Double[n];
        var altitude = new Double[n];
        for (int i = 0; i < n; i++) {
            distance[i] = i * 5.0;
            altitude[i] = distance[i] < 1_000 ? 100 : 100 + 0.06 * (distance[i] - 1_000);
        }
        var streams = new EnumMap<StreamType, Double[]>(StreamType.class);
        streams.put(StreamType.DISTANCE, distance);
        streams.put(StreamType.ALTITUDE, altitude);
        var id = ride(streams, n);

        mvc.perform(session.authorize(get("/api/v1/activities/" + id + "/climbs")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.algorithmVersion").value("climbs/1"))
                .andExpect(jsonPath("$.climbs.length()").value(1))
                .andExpect(jsonPath("$.climbs[0].averageGradePercent").value(Matchers.closeTo(6.0, 0.5)))
                .andExpect(jsonPath("$.gradeDistribution.length()").value(7));
    }

    @Test
    void withoutAltitudeThereIsNothingToShow() throws Exception {
        var heart = new Double[10];
        Arrays.fill(heart, 120.0);
        var streams = new EnumMap<StreamType, Double[]>(StreamType.class);
        streams.put(StreamType.HEART_RATE, heart);
        var id = ride(streams, 10);

        mvc.perform(session.authorize(get("/api/v1/activities/" + id + "/climbs")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.climbs").isEmpty());
        mvc.perform(session.authorize(get("/api/v1/activities/" + UUID.randomUUID() + "/climbs")))
                .andExpect(status().isNotFound());
    }
}
