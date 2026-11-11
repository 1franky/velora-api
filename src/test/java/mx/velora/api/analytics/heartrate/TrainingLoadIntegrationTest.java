package mx.velora.api.analytics.heartrate;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
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
class TrainingLoadIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final AtomicInteger SEED = new AtomicInteger(270);
    private static final ZoneId MEXICO_CITY = ZoneId.of("America/Mexico_City");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    @Autowired
    ActivityRegistration registration;

    @Autowired
    Clock clock;

    private TestSessions.Session session;

    @BeforeEach
    void login() throws Exception {
        session = TestSessions.freshOwner(mvc, jdbc, bootstrap);
    }

    /** Rodada al mediodía local del día dado, {@code seconds} muestras de 1 s a la FC dada (o sin FC). */
    private void ride(LocalDate day, int seconds, Double heartRate) {
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
                ActivitySubType.ROAD,
                day.atTime(12, 0).atZone(MEXICO_CITY).toInstant(),
                MEXICO_CITY,
                "FIT",
                null,
                List.of(
                        new MetricValue(Metric.ELAPSED_TIME, Source.DERIVED, seconds, "derived-metrics/1", null),
                        new MetricValue(Metric.MOVING_TIME, Source.DERIVED, seconds, "derived-metrics/1", null)),
                new NewActivity.Samples(seconds, new int[seconds], streams),
                List.of(),
                new NewActivity.OriginalFile(session.userId() + "/" + UUID.randomUUID(), sha, 1, "garmin-fit", "1")));
    }

    @Test
    void loadIsZoneMinutesTimesZoneNumberPerLocalWeek() throws Exception {
        mvc.perform(session.authorize(patch("/api/v1/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxHeartRateBpm\":190}")))
                .andExpect(status().isOk());
        var thisMonday =
                LocalDate.now(clock.withZone(MEXICO_CITY)).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        ride(thisMonday, 3_601, 140.0); // 60 min en Z3 → 180
        ride(thisMonday.minusWeeks(1), 1_201, 175.0); // 20 min en Z5 → 100
        ride(thisMonday.minusWeeks(1).plusDays(2), 1_200, null); // sin FC: no suma

        var body = mvc.perform(session.authorize(get("/api/v1/analytics/training-load?weeks=4")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.estimated").value(true))
                .andExpect(jsonPath("$.method").value("edwards-trimp/1"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat((List<?>) JsonPath.read(body, "$.weeks")).hasSize(4);
        assertThat((String) JsonPath.read(body, "$.weeks[3].start")).isEqualTo(thisMonday.toString());
        assertThat(((Number) JsonPath.read(body, "$.weeks[3].load")).doubleValue())
                .isEqualTo(180);
        assertThat(((Number) JsonPath.read(body, "$.weeks[2].load")).doubleValue())
                .isEqualTo(100);
        assertThat((Integer) JsonPath.read(body, "$.weeks[2].activitiesWithoutHeartRate"))
                .isEqualTo(1);
        assertThat(((Number) JsonPath.read(body, "$.weeks[0].load")).doubleValue())
                .isZero();
    }

    @Test
    void withoutAMaximumNothingIsEstimated() throws Exception {
        var today = LocalDate.now(clock.withZone(MEXICO_CITY));
        ride(today, 3_601, 140.0);

        mvc.perform(session.authorize(get("/api/v1/analytics/training-load?weeks=1")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.maxHeartRateBpm").doesNotExist())
                .andExpect(jsonPath("$.weeks[0].load").value(0.0))
                .andExpect(jsonPath("$.weeks[0].activitiesWithHeartRate").value(1));
        mvc.perform(session.authorize(get("/api/v1/analytics/training-load?weeks=0")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void zoneMinutesAreWeightedByZone() {
        assertThat(TrainingLoadController.load(new double[] {600, 60, 120, 180, 240, 300}))
                .isEqualTo(1 + 4 + 9 + 16 + 25);
    }
}
