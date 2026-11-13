package mx.velora.api.activity;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ActivityTrackIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final AtomicInteger SEED = new AtomicInteger(100);
    private static final int POINTS = 2_000;

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

    private UUID register(Map<StreamType, Double[]> streams, int[] segments) {
        var sha = new byte[32];
        Arrays.fill(sha, (byte) SEED.incrementAndGet());
        return registration.register(new NewActivity(
                session.userId(),
                null,
                null,
                null,
                ActivitySubType.MOUNTAIN_BIKE,
                Instant.parse("2026-11-08T14:00:00Z"),
                ZoneId.of("America/Mexico_City"),
                "GPX",
                null,
                List.of(
                        new MetricValue(Metric.ELAPSED_TIME, Source.DERIVED, POINTS, "derived-metrics/1", null),
                        new MetricValue(Metric.MOVING_TIME, Source.DERIVED, POINTS, "derived-metrics/1", null)),
                new NewActivity.Samples(segments.length, segments, streams),
                List.of(),
                new NewActivity.OriginalFile(session.userId() + "/" + UUID.randomUUID(), sha, 1, "velora-gpx", "1")));
    }

    /** Zigzag de 2 000 puntos en dos segmentos (pausa a la mitad). */
    private UUID zigzagRide() {
        var lat = new Double[POINTS];
        var lon = new Double[POINTS];
        var segments = new int[POINTS];
        for (int i = 0; i < POINTS; i++) {
            lat[i] = 19.0 + i * 0.00005;
            lon[i] = -99.0 + (i % 2 == 0 ? 0 : 0.00001) + Math.sin(i / 50.0) * 0.002;
            segments[i] = i < POINTS / 2 ? 0 : 1;
        }
        var streams = new EnumMap<StreamType, Double[]>(StreamType.class);
        streams.put(StreamType.LATITUDE, lat);
        streams.put(StreamType.LONGITUDE, lon);
        return register(streams, segments);
    }

    private String track(UUID id, int zoom) throws Exception {
        return mvc.perform(session.authorize(get("/api/v1/activities/" + id + "/track?zoom=" + zoom)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/geo+json"))
                .andExpect(openApi().isValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void returnsAMultiLineStringPerSegmentSimplifiedByZoom() throws Exception {
        var id = zigzagRide();

        var detailed = track(id, 22);
        var overview = track(id, 10);

        assertThat((String) JsonPath.read(detailed, "$.type")).isEqualTo("Feature");
        assertThat((String) JsonPath.read(detailed, "$.geometry.type")).isEqualTo("MultiLineString");
        assertThat((List<?>) JsonPath.read(detailed, "$.geometry.coordinates"))
                .as("dos tramos")
                .hasSize(2);
        assertThat((Integer) JsonPath.read(detailed, "$.properties.pointCount")).isEqualTo(POINTS);
        int detailedPoints = JsonPath.read(detailed, "$.properties.returnedPointCount");
        int overviewPoints = JsonPath.read(overview, "$.properties.returnedPointCount");
        assertThat(detailedPoints).isGreaterThan(POINTS * 9 / 10);
        assertThat(overviewPoints).isLessThan(detailedPoints / 5);
        List<Double> bbox = JsonPath.read(overview, "$.bbox");
        assertThat(bbox.get(1)).as("sur").isEqualTo(19.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(bbox.get(3))
                .as("norte")
                .isEqualTo(19.0 + (POINTS - 1) * 0.00005, org.assertj.core.data.Offset.offset(1e-9));
    }

    /** Criterio de aceptación (11, Geoespacial): simplificar para un zoom no modifica el track guardado. */
    @Test
    void simplifyingForAZoomNeverChangesTheStoredTrack() throws Exception {
        var id = zigzagRide();
        var stored = jdbc.sql("""
                        SELECT point_count, ST_NPoints(route_geom) AS points, md5(ST_AsBinary(route_geom)) AS hash
                        FROM activity_tracks WHERE activity_id = :id
                        """).param("id", id).query().singleRow();

        int overview = JsonPath.read(track(id, 3), "$.properties.returnedPointCount");
        track(id, 10);
        int full = JsonPath.read(track(id, 22), "$.properties.returnedPointCount");

        assertThat(overview).as("muy simplificado").isLessThan(POINTS / 20);
        assertThat(full)
                .as("después de simplificar, el detalle completo sigue ahí")
                .isGreaterThan(POINTS * 9 / 10);
        var after = jdbc.sql("""
                        SELECT point_count, ST_NPoints(route_geom) AS points, md5(ST_AsBinary(route_geom)) AS hash
                        FROM activity_tracks WHERE activity_id = :id
                        """).param("id", id).query().singleRow();
        assertThat(after).isEqualTo(stored);
        assertThat(((Number) after.get("points")).intValue()).isEqualTo(POINTS);
    }

    @Test
    void aRideWithoutPausesIsStillAMultiLineString() throws Exception {
        var lat = new Double[] {19.0, 19.01, 19.0, 19.03};
        var lon = new Double[] {-99.0, -98.99, -98.97, -98.95};
        var streams = new EnumMap<StreamType, Double[]>(StreamType.class);
        streams.put(StreamType.LATITUDE, lat);
        streams.put(StreamType.LONGITUDE, lon);
        var id = register(streams, new int[] {0, 0, 0, 0});

        var body = track(id, 10);
        assertThat((String) JsonPath.read(body, "$.geometry.type")).isEqualTo("MultiLineString");
        assertThat((List<?>) JsonPath.read(body, "$.geometry.coordinates")).hasSize(1);
    }

    @Test
    void indoorActivityHasNoTrack() throws Exception {
        var streams = new EnumMap<StreamType, Double[]>(StreamType.class);
        streams.put(StreamType.POWER, new Double[] {200.0, 210.0});
        var id = register(streams, new int[] {0, 0});

        mvc.perform(session.authorize(get("/api/v1/activities/" + id + "/track")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La actividad no tiene recorrido con GPS (p. ej. rodillo)."));
    }

    @Test
    void validatesZoomAndOwnership() throws Exception {
        var id = zigzagRide();

        mvc.perform(session.authorize(get("/api/v1/activities/" + id + "/track?zoom=23")))
                .andExpect(status().isBadRequest());
        mvc.perform(session.authorize(get("/api/v1/activities/" + UUID.randomUUID() + "/track")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La actividad no existe."));
        mvc.perform(get("/api/v1/activities/" + id + "/track")).andExpect(status().isUnauthorized());
    }
}
