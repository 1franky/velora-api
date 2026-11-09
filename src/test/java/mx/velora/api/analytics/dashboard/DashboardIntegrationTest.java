package mx.velora.api.analytics.dashboard;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class DashboardIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final AtomicInteger SEED = new AtomicInteger();

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

    private void ride(String startUtc, double distance, double moving, Double gain) {
        var metrics = new java.util.ArrayList<>(List.of(
                new MetricValue(Metric.ELAPSED_TIME, Source.DEVICE_REPORTED, moving, null, null),
                new MetricValue(Metric.MOVING_TIME, Source.DEVICE_REPORTED, moving, null, null),
                new MetricValue(Metric.DISTANCE, Source.DEVICE_REPORTED, distance, null, null)));
        if (gain != null) {
            metrics.add(new MetricValue(Metric.ELEVATION_GAIN, Source.DEVICE_REPORTED, gain, null, null));
        }
        var sha = new byte[32];
        Arrays.fill(sha, (byte) SEED.incrementAndGet());
        registration.register(new NewActivity(
                session.userId(),
                null,
                null,
                null,
                ActivitySubType.ROAD,
                Instant.parse(startUtc),
                ZoneId.of("America/Mexico_City"),
                "FIT",
                null,
                metrics,
                new NewActivity.Samples(1, new int[] {0}, Map.of(StreamType.TIME, new Double[] {0.0})),
                List.of(),
                new NewActivity.OriginalFile(session.userId() + "/" + UUID.randomUUID(), sha, 1, "velora-fit", "1")));
    }

    private ResultActions summary(String query) throws Exception {
        return mvc.perform(session.authorize(get("/api/v1/dashboard/summary?" + query)))
                .andExpect(openApi().isValid(CONTRACT));
    }

    @Test
    void monthTotalsComparisonTrendAndLatestActivity() throws Exception {
        ride("2026-11-02T14:00:00Z", 40_000, 7_200, 500.0);
        ride("2026-11-08T14:00:00Z", 60_000, 10_800, null); // sin desnivel: no rompe la suma
        ride("2026-10-15T14:00:00Z", 30_000, 5_400, 300.0);
        // 05:30Z del 01/11 = 23:30 del 31/10 en Ciudad de México: cuenta en octubre.
        ride("2026-11-01T05:30:00Z", 10_000, 1_800, 100.0);

        summary("period=month&date=2026-11-09")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("month"))
                .andExpect(jsonPath("$.timeZone").value("America/Mexico_City"))
                .andExpect(jsonPath("$.current.from").value("2026-11-01"))
                .andExpect(jsonPath("$.current.to").value("2026-11-30"))
                .andExpect(jsonPath("$.current.activityCount").value(2))
                .andExpect(jsonPath("$.current.distanceMeters").value(100_000.0))
                .andExpect(jsonPath("$.current.movingTimeSeconds").value(18_000))
                .andExpect(jsonPath("$.current.elevationGainMeters").value(500.0))
                .andExpect(jsonPath("$.previous.from").value("2026-10-01"))
                .andExpect(jsonPath("$.previous.activityCount").value(2))
                .andExpect(jsonPath("$.previous.distanceMeters").value(40_000.0))
                .andExpect(jsonPath("$.monthlyTrend", hasSize(12)))
                .andExpect(jsonPath("$.monthlyTrend[0].month").value("2025-12"))
                .andExpect(jsonPath("$.monthlyTrend[0].activityCount").value(0))
                .andExpect(jsonPath("$.monthlyTrend[10].month").value("2026-10"))
                .andExpect(jsonPath("$.monthlyTrend[10].activityCount").value(2))
                .andExpect(jsonPath("$.monthlyTrend[11].month").value("2026-11"))
                .andExpect(jsonPath("$.monthlyTrend[11].distanceMeters").value(100_000.0))
                .andExpect(jsonPath("$.latestActivity.startedAt").value("2026-11-08T14:00:00Z"));
    }

    @Test
    void weekAndAll() throws Exception {
        ride("2026-11-02T14:00:00Z", 40_000, 7_200, 500.0); // lunes
        ride("2026-11-01T18:00:00Z", 20_000, 3_600, 200.0); // domingo anterior

        summary("period=week&date=2026-11-08")
                .andExpect(jsonPath("$.current.from").value("2026-11-02"))
                .andExpect(jsonPath("$.current.activityCount").value(1))
                .andExpect(jsonPath("$.previous.activityCount").value(1));
        summary("period=all")
                .andExpect(jsonPath("$.current.from").isEmpty())
                .andExpect(jsonPath("$.current.activityCount").value(2))
                .andExpect(jsonPath("$.previous").isEmpty());
    }

    @Test
    void emptyHistoryHasZerosAndNoLatestActivity() throws Exception {
        summary("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("month"))
                .andExpect(jsonPath("$.current.activityCount").value(0))
                .andExpect(jsonPath("$.current.distanceMeters").value(0))
                .andExpect(jsonPath("$.latestActivity").isEmpty());
    }

    @Test
    void rejectsUnknownPeriod() throws Exception {
        mvc.perform(session.authorize(get("/api/v1/dashboard/summary?period=decade")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El periodo debe ser week, month, year o all."));
        mvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isUnauthorized());
    }
}
