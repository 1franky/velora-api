package mx.velora.api.analytics.records;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class RecordsIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final AtomicInteger SEED = new AtomicInteger(240);

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

    private UUID ride(String title, String startedAt, ActivitySubType type, double meters, double gain, int seconds) {
        var sha = new byte[32];
        Arrays.fill(sha, (byte) SEED.incrementAndGet());
        return registration.register(new NewActivity(
                session.userId(),
                null,
                null,
                title,
                type,
                Instant.parse(startedAt),
                ZoneId.of("America/Mexico_City"),
                "FIT",
                null,
                List.of(
                        new MetricValue(Metric.ELAPSED_TIME, Source.DERIVED, seconds, "derived-metrics/1", null),
                        new MetricValue(Metric.MOVING_TIME, Source.DERIVED, seconds, "derived-metrics/1", null),
                        new MetricValue(Metric.DISTANCE, Source.DEVICE_REPORTED, meters, null, null),
                        new MetricValue(Metric.ELEVATION_GAIN, Source.DEVICE_REPORTED, gain, null, null),
                        new MetricValue(
                                Metric.AVERAGE_SPEED, Source.DERIVED, meters / seconds, "derived-metrics/1", null)),
                new NewActivity.Samples(0, new int[0], new EnumMap<StreamType, Double[]>(StreamType.class)),
                List.of(),
                new NewActivity.OriginalFile(session.userId() + "/" + UUID.randomUUID(), sha, 1, "garmin-fit", "1")));
    }

    private String fetch(String path) throws Exception {
        return mvc.perform(session.authorize(get(path)))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static Object kind(String body, String kind, String field) {
        List<Object> values = JsonPath.read(body, "$.records[?(@.kind == '" + kind + "')]." + field);
        return values.isEmpty() ? null : values.getFirst();
    }

    @Test
    void appliesTheRulesForEachRecord() throws Exception {
        ride("Larga", "2026-10-04T14:00:00Z", ActivitySubType.ROAD, 120_000, 900, 5 * 3600);
        ride("Montaña", "2026-10-11T14:00:00Z", ActivitySubType.MOUNTAIN_BIKE, 45_000, 1_800, 4 * 3600);
        // Rapidísima pero corta: no cumple el mínimo de 10 km para el récord de velocidad.
        ride("Sprint", "2026-10-12T14:00:00Z", ActivitySubType.ROAD, 3_000, 10, 240);
        ride("Rápida", "2026-10-13T14:00:00Z", ActivitySubType.ROAD, 40_000, 200, 3_600);
        // Rodillo: la más larga en tiempo, pero no cuenta por omisión.
        ride("Rodillo", "2026-10-14T14:00:00Z", ActivitySubType.INDOOR, 150_000, 0, 6 * 3600);
        // Dos rodadas el mismo día suman el mejor día: 60 + 70 = 130 km > 120 km.
        ride("Mañana", "2026-10-18T13:00:00Z", ActivitySubType.ROAD, 60_000, 300, 2 * 3600);
        ride("Tarde", "2026-10-18T20:00:00Z", ActivitySubType.ROAD, 70_000, 300, 2 * 3600 + 600);

        var body = fetch("/api/v1/analytics/records");
        assertThat(kind(body, "longest_distance", "title")).isEqualTo("Larga");
        assertThat(kind(body, "most_elevation", "title")).isEqualTo("Montaña");
        assertThat(kind(body, "longest_moving_time", "title")).isEqualTo("Larga");
        assertThat(kind(body, "fastest_average_speed", "title")).isEqualTo("Rápida");
        assertThat(kind(body, "longest_distance", "source")).isEqualTo("DEVICE_REPORTED");
        assertThat((String) JsonPath.read(body, "$.biggestDay.date")).isEqualTo("2026-10-18");
        assertThat((Integer) JsonPath.read(body, "$.biggestDay.activityCount")).isEqualTo(2);

        body = fetch("/api/v1/analytics/records?includeIndoor=true");
        assertThat(kind(body, "longest_distance", "title")).isEqualTo("Rodillo");
        assertThat(kind(body, "longest_moving_time", "title")).isEqualTo("Rodillo");

        body = fetch("/api/v1/analytics/records?subType=MOUNTAIN_BIKE");
        assertThat(kind(body, "longest_distance", "title")).isEqualTo("Montaña");
        // 45 km en 4 h cumple los mínimos de calidad: es también su récord de velocidad.
        assertThat(kind(body, "fastest_average_speed", "title")).isEqualTo("Montaña");
    }

    @Test
    void computesEddingtonByLocalDayInKilometers() throws Exception {
        var empty = fetch("/api/v1/analytics/eddington");
        assertThat((Integer) JsonPath.read(empty, "$.overall.number")).isZero();
        assertThat((Object) JsonPath.read(fetch("/api/v1/analytics/records"), "$.biggestDay"))
                .isNull();

        // Tres días distintos de al menos 3 km: E = 3. El 4.º día tiene dos rodadas de 1.5 km en el mismo día local
        // (la segunda, 23:30 de la CDMX = otro día en UTC) que suman 3 km.
        ride("A", "2026-03-01T14:00:00Z", ActivitySubType.ROAD, 10_000, 0, 1_800);
        ride("B", "2026-03-02T14:00:00Z", ActivitySubType.ROAD, 8_000, 0, 1_800);
        ride("C", "2026-03-03T14:00:00Z", ActivitySubType.ROAD, 2_000, 0, 600);
        ride("D1", "2026-03-04T14:00:00Z", ActivitySubType.ROAD, 1_500, 0, 400);
        ride("D2", "2026-03-05T05:30:00Z", ActivitySubType.ROAD, 1_500, 0, 400);
        ride("Rodillo", "2026-03-06T14:00:00Z", ActivitySubType.INDOOR, 50_000, 0, 3_600);

        var body = fetch("/api/v1/analytics/eddington");
        assertThat((Integer) JsonPath.read(body, "$.overall.number")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(body, "$.daysWithRides")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(body, "$.overall.missingForNext")).isEqualTo(2);

        // El rodillo agrega un día de 50 km: E sigue en 3 (el día de 3 km no llega a 4), pero ya solo falta un día.
        body = fetch("/api/v1/analytics/eddington?includeIndoor=true");
        assertThat((Integer) JsonPath.read(body, "$.overall.number")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(body, "$.overall.missingForNext")).isEqualTo(1);
    }
}
