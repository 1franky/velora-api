package mx.velora.api.analytics.trends;

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
class TrendsIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final AtomicInteger SEED = new AtomicInteger(230);

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

    /** Rodada sin GPS con distancia, desnivel y tiempos dados; {@code streams} series de relleno (más datos). */
    private UUID ride(String startedAt, ActivitySubType type, double meters, double gain, int seconds, int streams) {
        var sha = new byte[32];
        Arrays.fill(sha, (byte) SEED.incrementAndGet());
        var samples = new EnumMap<StreamType, Double[]>(StreamType.class);
        var kinds = List.of(StreamType.HEART_RATE, StreamType.CADENCE, StreamType.POWER);
        for (int i = 0; i < streams; i++) {
            var values = new Double[10];
            Arrays.fill(values, 100.0);
            samples.put(kinds.get(i), values);
        }
        return registration.register(new NewActivity(
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
                        new MetricValue(Metric.MOVING_TIME, Source.DERIVED, seconds, "derived-metrics/1", null),
                        new MetricValue(Metric.DISTANCE, Source.DEVICE_REPORTED, meters, null, null),
                        new MetricValue(Metric.ELEVATION_GAIN, Source.DEVICE_REPORTED, gain, null, null)),
                new NewActivity.Samples(10, new int[10], samples),
                List.of(),
                new NewActivity.OriginalFile(session.userId() + "/" + UUID.randomUUID(), sha, 1, "garmin-fit", "1")));
    }

    private String series(String query) throws Exception {
        return mvc.perform(session.authorize(get("/api/v1/analytics/timeseries?" + query)))
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
    void sumsByLocalWeekAndFillsEmptyWeeksWithZeros() throws Exception {
        // Lunes 2 y domingo 8 de noviembre (misma semana), y el lunes 16 (la semana del 9 queda vacía).
        ride("2026-11-02T14:00:00Z", ActivitySubType.ROAD, 40_000, 300, 5_400, 0);
        // Domingo 8 a las 23:30 en la CDMX = lunes 9 en UTC: cuenta en la semana local del 2.
        ride("2026-11-09T05:30:00Z", ActivitySubType.GRAVEL, 20_000, 200, 3_600, 0);
        ride("2026-11-16T14:00:00Z", ActivitySubType.ROAD, 30_000, 100, 4_000, 0);

        var body = series("granularity=week&from=2026-11-02&to=2026-11-22");
        assertThat((List<?>) JsonPath.read(body, "$.buckets")).hasSize(3);
        assertThat((String) JsonPath.read(body, "$.buckets[0].start")).isEqualTo("2026-11-02");
        assertThat((String) JsonPath.read(body, "$.buckets[0].end")).isEqualTo("2026-11-08");
        assertThat(number(body, "$.buckets[0].distanceMeters")).isEqualTo(60_000);
        assertThat(number(body, "$.buckets[0].activityCount")).isEqualTo(2);
        assertThat(number(body, "$.buckets[1].activityCount")).isZero();
        assertThat(number(body, "$.buckets[2].elevationGainMeters")).isEqualTo(100);
        assertThat(number(body, "$.totals.distanceMeters")).isEqualTo(90_000);
        assertThat(number(body, "$.distanceSources.deviceReported")).isEqualTo(3);

        body = series("granularity=month&from=2026-11-01&to=2026-11-30&subType=ROAD");
        assertThat(number(body, "$.buckets[0].distanceMeters")).isEqualTo(70_000);
    }

    @Test
    void leavesIndoorOutUnlessAskedFor() throws Exception {
        ride("2026-11-02T14:00:00Z", ActivitySubType.ROAD, 40_000, 300, 5_400, 0);
        ride("2026-11-03T14:00:00Z", ActivitySubType.INDOOR, 25_000, 0, 3_600, 0);

        assertThat(number(series("granularity=month&from=2026-11-01&to=2026-11-30"), "$.totals.distanceMeters"))
                .isEqualTo(40_000);
        var body = series("granularity=month&from=2026-11-01&to=2026-11-30&includeIndoor=true");
        assertThat(number(body, "$.totals.distanceMeters")).isEqualTo(65_000);
        assertThat((Boolean) JsonPath.read(body, "$.includeIndoor")).isTrue();
    }

    @Test
    void countsOnlyTheRicherCopyOfAnUnresolvedDuplicate() throws Exception {
        // La misma rodada en dos archivos: misma hora (±1 min), duración y distancia casi iguales.
        var poorer = ride("2026-11-02T14:00:00Z", ActivitySubType.ROAD, 40_000, 300, 5_400, 0);
        var richer = ride("2026-11-02T14:01:00Z", ActivitySubType.ROAD, 40_400, 310, 5_420, 2);
        ride("2026-11-02T20:00:00Z", ActivitySubType.ROAD, 10_000, 50, 1_800, 0); // otra rodada del mismo día

        var body = series("granularity=month&from=2026-11-01&to=2026-11-30");
        assertThat(number(body, "$.totals.activityCount")).isEqualTo(2);
        assertThat(number(body, "$.totals.distanceMeters")).isEqualTo(50_400);
        assertThat(number(body, "$.duplicatesExcluded")).isEqualTo(1);

        var counted = jdbc.sql("SELECT id FROM counted_activities WHERE user_id = :u")
                .param("u", session.userId())
                .query(UUID.class)
                .list();
        assertThat(counted).contains(richer).doesNotContain(poorer);

        // El dashboard sigue la misma regla.
        mvc.perform(session.authorize(get("/api/v1/dashboard/summary?period=month&date=2026-11-15")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.activityCount").value(2));
    }

    @Test
    void defaultsToTwelveWeeksUntilTodayAndRejectsBadRanges() throws Exception {
        var body = series("");
        assertThat((List<?>) JsonPath.read(body, "$.buckets")).hasSize(12);
        assertThat((String) JsonPath.read(body, "$.granularity")).isEqualTo("week");

        for (var query : List.of("granularity=day", "from=2026-11-10&to=2026-11-01", "from=2000-01-01&to=2026-11-01")) {
            mvc.perform(session.authorize(get("/api/v1/analytics/timeseries?" + query)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Usa granularity=")));
        }
    }
}
