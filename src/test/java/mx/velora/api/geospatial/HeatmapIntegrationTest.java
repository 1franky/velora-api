package mx.velora.api.geospatial;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import java.nio.charset.StandardCharsets;
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
import mx.velora.api.activity.TrackPrivacy;
import mx.velora.api.contract.OpenApiContract;
import mx.velora.api.identity.user.OwnerBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class HeatmapIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final AtomicInteger SEED = new AtomicInteger(140);
    private static final double LON = -99.0;
    private static final double STEP = 0.00005; // ~5.56 m
    private static final int Z = 16;
    /** Tesela del zoom 16 sobre la longitud de las rodadas y su borde norte. */
    private static final int TILE_X = tileX(LON, Z);

    private static final int TILE_Y = tileY(19.0, Z);
    private static final double TILE_NORTH = tileNorth(TILE_Y, Z);

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

    /** Recta hacia el norte de ~11 km que sale ~33 m al sur del borde norte de la tesela de prueba. */
    private UUID ride(ActivitySubType subType) {
        var points = 2_000;
        var sha = new byte[32];
        Arrays.fill(sha, (byte) SEED.incrementAndGet());
        var lat = new Double[points];
        var lon = new Double[points];
        for (int i = 0; i < points; i++) {
            lat[i] = TILE_NORTH - 0.0003 + i * STEP;
            lon[i] = LON;
        }
        var streams = new EnumMap<StreamType, Double[]>(StreamType.class);
        streams.put(StreamType.LATITUDE, lat);
        streams.put(StreamType.LONGITUDE, lon);
        return registration.register(new NewActivity(
                session.userId(),
                null,
                null,
                "Recta",
                subType,
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

    private byte[] tile(int z, int x, int y, String query) throws Exception {
        var response = mvc.perform(session.authorize(get("/api/v1/geo/heatmap/" + z + "/" + x + "/" + y + query)))
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")))
                .andReturn()
                .getResponse();
        if (response.getStatus() == 204) {
            return new byte[0];
        }
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/vnd.mapbox-vector-tile");
        return response.getContentAsByteArray();
    }

    private static boolean hasRoutes(byte[] tile) {
        return tile.length > 0 && new String(tile, StandardCharsets.ISO_8859_1).contains("recorridos");
    }

    @Test
    void drawsTheRidesThatCrossTheTileAndRevalidatesWithEtag() throws Exception {
        ride(ActivitySubType.ROAD);
        var middle = tileY(TILE_NORTH + 1_000 * STEP, 14);

        assertThat(hasRoutes(tile(14, tileX(LON, 14), middle, ""))).isTrue();
        assertThat(tile(14, tileX(LON, 14) + 3, middle, ""))
                .as("lejos del recorrido")
                .isEmpty();

        var etag = mvc.perform(session.authorize(get("/api/v1/geo/heatmap/14/" + tileX(LON, 14) + "/" + middle)))
                .andReturn()
                .getResponse()
                .getHeader("ETag");
        mvc.perform(session.authorize(get("/api/v1/geo/heatmap/14/" + tileX(LON, 14) + "/" + middle))
                        .header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void hidesTheStartWhenPrivacyIsOn() throws Exception {
        ride(ActivitySubType.ROAD);
        assertThat(hasRoutes(tile(Z, TILE_X, TILE_Y, "")))
                .as("sin privacidad se ve la salida")
                .isTrue();

        mvc.perform(session.authorize(patch("/api/v1/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackPrivacyMeters\":300}")))
                .andExpect(status().isOk());

        // Entre 210 y 390 m ocultos: los ~33 m que la rodada recorre dentro de la tesela desaparecen.
        assertThat(tile(Z, TILE_X, TILE_Y, "")).isEmpty();
        assertThat(hasRoutes(tile(14, tileX(LON, 14), tileY(TILE_NORTH + 1_000 * STEP, 14), "")))
                .as("el resto del recorrido sigue")
                .isTrue();
    }

    @Test
    void appliesTheFilters() throws Exception {
        ride(ActivitySubType.ROAD);
        assertThat(hasRoutes(tile(Z, TILE_X, TILE_Y, "?subType=ROAD&from=2026-11-08&to=2026-11-08")))
                .isTrue();
        assertThat(tile(Z, TILE_X, TILE_Y, "?subType=GRAVEL")).isEmpty();
        assertThat(tile(Z, TILE_X, TILE_Y, "?from=2026-11-09")).isEmpty();
        assertThat(tile(Z, TILE_X, TILE_Y, "?bikeId=" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void rejectsTilesOutsideTheGridAndInvertedRanges() throws Exception {
        for (var path : List.of("17/0/0", "2/4/0", "2/0/4", "3/1/1?from=2026-11-09&to=2026-11-08")) {
            mvc.perform(session.authorize(get("/api/v1/geo/heatmap/" + path)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("La tesela no existe")));
        }
    }

    @Test
    void requiresASession() throws Exception {
        mvc.perform(get("/api/v1/geo/heatmap/0/0/0")).andExpect(status().isUnauthorized());
    }

    @Test
    void theDatabaseHidesTheSameDistanceAsTheTrackEndpoint() {
        for (int i = 0; i < 50; i++) {
            var id = UUID.randomUUID();
            var trim = TrackPrivacy.forActivity(id, 1_000).orElseThrow();
            var start = jdbc.sql("SELECT velora_privacy_factor(:id, true)")
                    .param("id", id)
                    .query(Double.class)
                    .single();
            var end = jdbc.sql("SELECT velora_privacy_factor(:id, false)")
                    .param("id", id)
                    .query(Double.class)
                    .single();
            assertThat(start * 1_000).isCloseTo(trim.startMeters(), org.assertj.core.data.Offset.offset(1e-9));
            assertThat(end * 1_000).isCloseTo(trim.endMeters(), org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    private static int tileX(double lon, int z) {
        return (int) Math.floor((lon + 180) / 360 * (1 << z));
    }

    private static int tileY(double lat, int z) {
        var rad = Math.toRadians(lat);
        return (int) Math.floor((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * (1 << z));
    }

    private static double tileNorth(int y, int z) {
        var n = Math.PI - 2 * Math.PI * y / (1 << z);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }
}
