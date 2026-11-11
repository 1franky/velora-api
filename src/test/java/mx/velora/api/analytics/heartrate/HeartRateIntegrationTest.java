package mx.velora.api.analytics.heartrate;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class HeartRateIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final AtomicInteger SEED = new AtomicInteger(260);
    private static final String RANGE = "from=2026-11-01&to=2026-11-30";

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

    /** {@code seconds} muestras de 1 s a la FC dada (sin FC si es null). */
    private void ride(String startedAt, ActivitySubType type, int seconds, Double heartRate) {
        var sha = new byte[32];
        Arrays.fill(sha, (byte) SEED.incrementAndGet());
        var streams = new EnumMap<StreamType, Double[]>(StreamType.class);
        var time = new Double[seconds];
        for (int i = 0; i < seconds; i++) time[i] = (double) i;
        streams.put(StreamType.TIME, time);
        if (heartRate != null) {
            var hr = new Double[seconds];
            Arrays.fill(hr, heartRate);
            streams.put(StreamType.HEART_RATE, hr);
        }
        registration.register(new NewActivity(
                session.userId(),
                null,
                null,
                "Rodada",
                type,
                Instant.parse(startedAt),
                ZoneId.of("America/Mexico_City"),
                "FIT",
                null,
                List.of(
                        new MetricValue(Metric.ELAPSED_TIME, Source.DERIVED, seconds, "derived-metrics/1", null),
                        new MetricValue(Metric.MOVING_TIME, Source.DERIVED, seconds, "derived-metrics/1", null)),
                new NewActivity.Samples(seconds, new int[seconds], streams),
                List.of(),
                new NewActivity.OriginalFile(session.userId() + "/" + UUID.randomUUID(), sha, 1, "garmin-fit", "1")));
    }

    private void maxHeartRate(int bpm) throws Exception {
        mvc.perform(session.authorize(patch("/api/v1/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxHeartRateBpm\":" + bpm + "}")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT));
    }

    @Test
    void withoutAMaximumThereAreNoZonesButTheRidesAreCounted() throws Exception {
        ride("2026-11-02T14:00:00Z", ActivitySubType.ROAD, 600, 150.0);
        ride("2026-11-03T14:00:00Z", ActivitySubType.ROAD, 600, null);

        mvc.perform(session.authorize(get("/api/v1/analytics/heart-rate?" + RANGE)))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.maxHeartRateBpm").doesNotExist())
                .andExpect(jsonPath("$.zones").isEmpty())
                .andExpect(jsonPath("$.activitiesWithHeartRate").value(1))
                .andExpect(jsonPath("$.activitiesWithoutHeartRate").value(1));
    }

    @Test
    void sumsTheTimeInEachZoneOfTheUsersMaximum() throws Exception {
        maxHeartRate(190);
        ride("2026-11-02T14:00:00Z", ActivitySubType.ROAD, 601, 140.0); // Z3: 600 s
        ride("2026-11-04T14:00:00Z", ActivitySubType.ROAD, 301, 175.0); // Z5: 300 s
        ride("2026-11-05T14:00:00Z", ActivitySubType.INDOOR, 1_001, 160.0); // Z4, solo con rodillo

        mvc.perform(session.authorize(get("/api/v1/analytics/heart-rate?" + RANGE)))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.maxHeartRateBpm").value(190))
                .andExpect(jsonPath("$.zones[2].fromBpm").value(133))
                .andExpect(jsonPath("$.zones[2].toBpm").value(151))
                .andExpect(jsonPath("$.zones[2].seconds").value(600.0))
                .andExpect(jsonPath("$.zones[3].seconds").value(0.0))
                .andExpect(jsonPath("$.zones[4].seconds").value(300.0))
                .andExpect(jsonPath("$.zones[4].toBpm").doesNotExist());

        mvc.perform(session.authorize(get("/api/v1/analytics/heart-rate?" + RANGE + "&includeIndoor=true")))
                .andExpect(jsonPath("$.zones[3].seconds").value(1_000.0));
    }

    @Test
    void validatesTheMaximumAndAllowsClearingIt() throws Exception {
        maxHeartRate(185);
        mvc.perform(session.authorize(get("/api/v1/me")))
                .andExpect(jsonPath("$.preferences.maxHeartRateBpm").value(185));
        mvc.perform(session.authorize(patch("/api/v1/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxHeartRateBpm\":90}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("La FC máxima debe estar entre 100 y 230 ppm (0 la borra)."));
        maxHeartRate(0);
        mvc.perform(session.authorize(get("/api/v1/me")))
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.preferences.maxHeartRateBpm").doesNotExist());
        // Las otras preferencias no cambian al tocar solo la FC.
        mvc.perform(session.authorize(get("/api/v1/me")))
                .andExpect(jsonPath("$.preferences.trackPrivacyMeters").value(0));
    }

    @Test
    void rejectsInvertedOrTooLongRanges() throws Exception {
        for (var query : List.of("from=2026-11-10&to=2026-11-01", "from=2020-01-01&to=2026-11-01")) {
            mvc.perform(session.authorize(get("/api/v1/analytics/heart-rate?" + query)))
                    .andExpect(status().isBadRequest());
        }
    }
}
