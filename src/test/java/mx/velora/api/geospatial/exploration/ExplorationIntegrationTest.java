package mx.velora.api.geospatial.exploration;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
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
class ExplorationIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final AtomicInteger SEED = new AtomicInteger(170);
    private static final double STEP = 0.00005; // ~5.56 m
    /** Recuadro que contiene las rodadas de prueba (CDMX, al sur del Zócalo). */
    private static final String BBOX = "-99.1,18.95,-98.9,19.2";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    @Autowired
    ActivityRegistration registration;

    @Autowired
    ExplorationUpdates updates;

    private TestSessions.Session session;

    @BeforeEach
    void login() throws Exception {
        session = TestSessions.freshOwner(mvc, jdbc, bootstrap);
    }

    /** Recta hacia el norte de ~11 km desde (19.0, -99.0); sin GPS si {@code indoor}. */
    private UUID ride(boolean indoor) {
        var points = 2_000;
        var sha = new byte[32];
        Arrays.fill(sha, (byte) SEED.incrementAndGet());
        var streams = new EnumMap<StreamType, Double[]>(StreamType.class);
        if (!indoor) {
            var lat = new Double[points];
            var lon = new Double[points];
            for (int i = 0; i < points; i++) {
                lat[i] = 19.0 + i * STEP;
                lon[i] = -99.0;
            }
            streams.put(StreamType.LATITUDE, lat);
            streams.put(StreamType.LONGITUDE, lon);
        }
        return registration.register(new NewActivity(
                session.userId(),
                null,
                null,
                indoor ? "Rodillo" : "Recta",
                indoor ? ActivitySubType.INDOOR : ActivitySubType.ROAD,
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

    private Integer privacyOfRun(UUID activityId) {
        return jdbc.sql("SELECT privacy_meters FROM exploration_runs WHERE activity_id = :id")
                .param("id", activityId)
                .query(Integer.class)
                .optional()
                .orElse(null);
    }

    private String summary() throws Exception {
        return mvc.perform(session.authorize(get("/api/v1/geo/exploration/summary")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private long hexagons() {
        return jdbc.sql("SELECT count(*) FROM exploration_cells WHERE user_id = :u AND grid = 'H3'")
                .param("u", session.userId())
                .query(Long.class)
                .single();
    }

    @Test
    void computesTheCellsOfAnImportedRideInTheBackground() throws Exception {
        var id = ride(false);
        await().atMost(Duration.ofSeconds(20)).until(() -> privacyOfRun(id) != null);

        var body = summary();
        assertThat((Integer) JsonPath.read(body, "$.processedActivities")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.pendingActivities")).isZero();
        // ~11.1 km hacia el norte: cruza 12 o 13 filas de la cuadrícula de 1 km (una sola columna, salvo borde).
        assertThat((Integer) JsonPath.read(body, "$.metricGrid.cellCount")).isBetween(12, 14);
        assertThat((Double) JsonPath.read(body, "$.metricGrid.areaKm2")).isBetween(12.0, 14.0);
        // Hexágonos de ~350 m de ancho: entre 30 y 70 a lo largo de 11 km.
        assertThat((Integer) JsonPath.read(body, "$.h3.cellCount")).isBetween(30, 70);
        assertThat((String) JsonPath.read(body, "$.algorithmVersion")).isEqualTo("exploration-cells/1");

        for (var grid : List.of("MX1KM", "H3")) {
            var cells = mvc.perform(
                            session.authorize(get("/api/v1/geo/exploration/cells?grid=" + grid + "&bbox=" + BBOX)))
                    .andExpect(status().isOk())
                    .andExpect(openApi().isValid(CONTRACT))
                    .andExpect(jsonPath("$.truncated").value(false))
                    .andExpect(
                            jsonPath("$.features[0].properties.activityCount").value(1))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            List<Object> features = JsonPath.read(cells, "$.features");
            assertThat(features).isNotEmpty();
        }
        mvc.perform(session.authorize(get("/api/v1/geo/exploration/cells?grid=MX1KM&bbox=10,10,11,11")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features").isEmpty());
    }

    /**
     * Criterio de aceptación (11, Geoespacial): las celdas no se duplican. Dos rodadas por el mismo camino (p. ej. el
     * FIT y el GPX de la misma salida, o la ruta de diario) cuentan cada celda una vez; volver a calcular no agrega
     * filas.
     */
    @Test
    void cellsAreNotDuplicatedByRepeatedRidesOrRecomputing() throws Exception {
        var first = ride(false);
        await().atMost(Duration.ofSeconds(20)).until(() -> privacyOfRun(first) != null);
        var alone = summary();
        int squares = JsonPath.read(alone, "$.metricGrid.cellCount");
        int hexes = JsonPath.read(alone, "$.h3.cellCount");

        var second = ride(false);
        await().atMost(Duration.ofSeconds(20)).until(() -> privacyOfRun(second) != null);
        var both = summary();

        assertThat((Integer) JsonPath.read(both, "$.processedActivities")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(both, "$.metricGrid.cellCount")).isEqualTo(squares);
        assertThat((Integer) JsonPath.read(both, "$.h3.cellCount")).isEqualTo(hexes);
        var rows = hexagons();

        // Recalcular todo (como al subir la versión del algoritmo) deja las mismas filas.
        jdbc.sql("UPDATE exploration_runs SET algorithm_version = 'exploration-cells/0' WHERE user_id = :u")
                .param("u", session.userId())
                .update();
        assertThat((Integer) JsonPath.read(summary(), "$.pendingActivities")).isEqualTo(2);
        updates.catchUp(null);
        assertThat((Integer) JsonPath.read(summary(), "$.pendingActivities")).isZero();
        assertThat(hexagons()).isEqualTo(rows);
        assertThat((Integer) JsonPath.read(summary(), "$.h3.cellCount")).isEqualTo(hexes);
    }

    @Test
    void recomputesWithThePrivacyAndForgetsDeletedActivities() throws Exception {
        var id = ride(false);
        await().atMost(Duration.ofSeconds(20)).until(() -> privacyOfRun(id) != null);
        var before = hexagons();

        mvc.perform(session.authorize(patch("/api/v1/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackPrivacyMeters\":500}")))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(20)).until(() -> Integer.valueOf(500).equals(privacyOfRun(id)));
        // Entre 350 y 650 m ocultos en cada extremo: desaparecen los hexágonos de la salida y la llegada.
        assertThat(hexagons()).isLessThan(before);

        mvc.perform(session.authorize(delete("/api/v1/activities/" + id))).andExpect(status().isNoContent());
        var body = summary();
        assertThat((Integer) JsonPath.read(body, "$.h3.cellCount")).isZero();
        assertThat((Integer) JsonPath.read(body, "$.processedActivities")).isZero();
    }

    @Test
    void ridesWithoutGpsHaveNoCellsAndAreNotPending() throws Exception {
        var id = ride(true);
        updates.catchUp(session.userId());

        assertThat(privacyOfRun(id)).isNull();
        assertThat((Integer) JsonPath.read(summary(), "$.pendingActivities")).isZero();
    }

    @Test
    void catchesUpActivitiesComputedWithAnotherVersion() throws Exception {
        var id = ride(false);
        await().atMost(Duration.ofSeconds(20)).until(() -> privacyOfRun(id) != null);
        jdbc.sql("UPDATE exploration_runs SET algorithm_version = 'exploration-cells/0' WHERE activity_id = :id")
                .param("id", id)
                .update();
        assertThat((Integer) JsonPath.read(summary(), "$.pendingActivities")).isEqualTo(1);

        updates.catchUp(null);

        assertThat((Integer) JsonPath.read(summary(), "$.pendingActivities")).isZero();
    }

    @Test
    void findsTheLargestSquareOfVisitedKilometerCells() throws Exception {
        mvc.perform(session.authorize(get("/api/v1/geo/exploration/largest-square")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.size").value(0))
                .andExpect(jsonPath("$.bbox").doesNotExist());

        var id = ride(false);
        await().atMost(Duration.ofSeconds(20)).until(() -> privacyOfRun(id) != null);
        // Bloque de 3 × 3 km junto a la recta (celdas sintéticas de la misma actividad).
        var start = jdbc.sql("SELECT min(cell) FROM exploration_cells WHERE user_id = :u AND grid = 'MX1KM'")
                .param("u", session.userId())
                .query(Long.class)
                .single();
        var corner = MetricCell.decode(start);
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 3; r++) {
                jdbc.sql("""
                                INSERT INTO exploration_cells (activity_id, user_id, grid, resolution, cell)
                                VALUES (:a, :u, 'MX1KM', 1000, :cell) ON CONFLICT DO NOTHING""")
                        .param("a", id)
                        .param("u", session.userId())
                        .param("cell", new MetricCell(corner.col() + 1 + c, corner.row() + r).encode())
                        .update();
            }
        }

        var body = mvc.perform(session.authorize(get("/api/v1/geo/exploration/largest-square")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.sideKm").value(3.0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        double south = JsonPath.read(body, "$.bbox[1]");
        double north = JsonPath.read(body, "$.bbox[3]");
        assertThat((north - south) * 111.2).isBetween(2.9, 3.2);
    }

    @Test
    void rejectsBadQueries() throws Exception {
        for (var query : List.of("grid=HEX&bbox=" + BBOX, "bbox=1,2,3", "bbox=10,0,5,1", "bbox=a,b,c,d")) {
            mvc.perform(session.authorize(get("/api/v1/geo/exploration/cells?" + query)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Usa grid=H3")));
        }
    }
}
