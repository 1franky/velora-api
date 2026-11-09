package mx.velora.api.activity;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import mx.velora.api.IntegrationTest;
import mx.velora.api.TestSessions;
import mx.velora.api.activity.Metric.Source;
import mx.velora.api.activity.NewActivity.MetricValue;
import mx.velora.api.contract.OpenApiContract;
import mx.velora.api.identity.user.OwnerBootstrap;
import mx.velora.api.importing.job.ImportWorker;
import mx.velora.api.platform.storage.OriginalFileStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class ActivityQueriesIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final ZoneId MEXICO_CITY = ZoneId.of("America/Mexico_City");
    private static final AtomicInteger SEED = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    @Autowired
    ActivityRegistration registration;

    @Autowired
    OriginalFileStore store;

    @Autowired
    ImportWorker worker;

    private TestSessions.Session session;

    @BeforeEach
    void login() throws Exception {
        session = TestSessions.freshOwner(mvc, jdbc, bootstrap);
    }

    /** Actividad sintética con track de 3 puntos, altitud con un hueco y una vuelta. */
    private UUID activity(Instant start, @Nullable Double distance, ActivitySubType subType, @Nullable UUID bikeId)
            throws Exception {
        var streams = new EnumMap<StreamType, Double[]>(StreamType.class);
        streams.put(StreamType.TIME, new Double[] {0.0, 1_000.0, 2_000.0});
        streams.put(StreamType.LATITUDE, new Double[] {19.0, 19.0001, 19.0002});
        streams.put(StreamType.LONGITUDE, new Double[] {-99.0, -99.0, -99.0});
        streams.put(StreamType.ALTITUDE, new Double[] {1_500.0, null, 1_501.5});
        var metrics = new ArrayList<MetricValue>(List.of(
                new MetricValue(Metric.ELAPSED_TIME, Source.DERIVED, 2, "derived-metrics/1", null),
                new MetricValue(Metric.MOVING_TIME, Source.DEVICE_REPORTED, 2, null, null)));
        if (distance != null) {
            metrics.add(new MetricValue(Metric.DISTANCE, Source.DERIVED, distance, "derived-metrics/1", null));
        }
        var stored = store.store(
                session.userId(), new ByteArrayInputStream(("archivo " + SEED.incrementAndGet()).getBytes()));
        return registration.register(new NewActivity(
                session.userId(),
                bikeId,
                null,
                null,
                subType,
                start,
                MEXICO_CITY,
                "FIT",
                null,
                metrics,
                new NewActivity.Samples(3, new int[] {0, 0, 0}, streams),
                List.of(new NewActivity.Lap(1, start, 2.0, 2.0, distance, null, null, 120, 130, null, null, null)),
                new NewActivity.OriginalFile(
                        stored.storageKey(), stored.sha256(), stored.sizeBytes(), "velora-fit", "1")));
    }

    private UUID activity(Instant start) throws Exception {
        return activity(start, 10_000.0, ActivitySubType.ROAD, null);
    }

    private ResultActions getJson(String path) throws Exception {
        return mvc.perform(session.authorize(get(path)));
    }

    private List<String> ids(String json) {
        return JsonPath.read(json, "$.items[*].id");
    }

    @Test
    void paginatesNewestFirstWithAStableCursor() throws Exception {
        var created = new ArrayList<String>();
        for (int day = 1; day <= 5; day++) {
            created.add(
                    activity(Instant.parse("2026-11-0" + day + "T14:00:00Z")).toString());
        }

        var first = getJson("/api/v1/activities?limit=2")
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(ids(first)).containsExactly(created.get(4), created.get(3));
        String cursor = JsonPath.read(first, "$.nextCursor");

        // Una importación nueva y más reciente entre páginas no desplaza el resto.
        activity(Instant.parse("2026-11-08T14:00:00Z"));

        var second = getJson("/api/v1/activities?limit=2&cursor=" + cursor)
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(ids(second)).containsExactly(created.get(2), created.get(1));
        var third = getJson("/api/v1/activities?limit=2&cursor=" + JsonPath.read(second, "$.nextCursor"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(ids(third)).containsExactly(created.get(0));
        assertThat((Object) JsonPath.read(third, "$.nextCursor")).isNull();
    }

    @Test
    void summaryIncludesLocalDateAndNullDistance() throws Exception {
        // 05:30Z del 09/11 = 23:30 del 08/11 en Ciudad de México.
        activity(Instant.parse("2026-11-09T05:30:00Z"), null, ActivitySubType.INDOOR, null);

        getJson("/api/v1/activities")
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.items[0].localStartDate").value("2026-11-08"))
                .andExpect(jsonPath("$.items[0].title").value("Rodada del 08/11/2026"))
                .andExpect(jsonPath("$.items[0].distanceMeters").isEmpty())
                .andExpect(jsonPath("$.items[0].movingTimeSeconds").value(2));
    }

    @Test
    void filtersByLocalDateTypeBikeAndDistance() throws Exception {
        var bike = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO bikes (id, user_id, name, bike_type, created_at, updated_at) VALUES (:id, :u, 'B', 'MTB', now(), now())")
                .param("id", bike)
                .param("u", session.userId())
                .update();
        var nightRide = activity(Instant.parse("2026-11-09T05:30:00Z"), 5_000.0, ActivitySubType.MOUNTAIN_BIKE, bike);
        var road = activity(Instant.parse("2026-11-09T15:00:00Z"), 80_000.0, ActivitySubType.ROAD, null);
        var indoor = activity(Instant.parse("2026-11-10T15:00:00Z"), null, ActivitySubType.INDOOR, null);

        assertThat(ids(getJson("/api/v1/activities?from=2026-11-08&to=2026-11-08")
                        .andReturn()
                        .getResponse()
                        .getContentAsString()))
                .containsExactly(nightRide.toString());
        assertThat(ids(getJson("/api/v1/activities?subType=INDOOR")
                        .andReturn()
                        .getResponse()
                        .getContentAsString()))
                .containsExactly(indoor.toString());
        assertThat(ids(getJson("/api/v1/activities?bikeId=" + bike)
                        .andReturn()
                        .getResponse()
                        .getContentAsString()))
                .containsExactly(nightRide.toString());
        assertThat(ids(getJson("/api/v1/activities?minDistanceM=10000")
                        .andReturn()
                        .getResponse()
                        .getContentAsString()))
                .as("sin distancia no cumple un mínimo")
                .containsExactly(road.toString());
        assertThat(ids(getJson("/api/v1/activities?maxDistanceM=10000")
                        .andReturn()
                        .getResponse()
                        .getContentAsString()))
                .containsExactly(nightRide.toString());
    }

    @Test
    void rejectsInvalidQueries() throws Exception {
        for (var query : List.of(
                "limit=0",
                "limit=101",
                "from=2026-11-09&to=2026-11-01",
                "minDistanceM=10&maxDistanceM=5",
                "cursor=no-es-un-cursor",
                "bikeId=no-es-uuid",
                "subType=BMX",
                "from=09/11/2026")) {
            getJson("/api/v1/activities?" + query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Solicitud inválida"));
        }
    }

    @Test
    void detailShowsProvenanceStreamsTrackAndFileWithoutStorageKey() throws Exception {
        var id = activity(Instant.parse("2026-11-09T15:00:00Z"));

        var json = getJson("/api/v1/activities/" + id)
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.timeZone").value("America/Mexico_City"))
                .andExpect(jsonPath("$.metricsProvenance.movingTimeSeconds").value("DEVICE_REPORTED"))
                .andExpect(jsonPath("$.metricsProvenance.distanceMeters").value("DERIVED"))
                .andExpect(jsonPath("$.hasTrack").value(true))
                .andExpect(jsonPath("$.streamTypes", hasSize(4)))
                .andExpect(jsonPath("$.lapCount").value(1))
                .andExpect(jsonPath("$.originalFile.format").value("FIT"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(json).doesNotContain(session.userId() + "/").doesNotContain("storage");
    }

    @Test
    void streamsPreserveGapsAndCanBeFiltered() throws Exception {
        var id = activity(Instant.parse("2026-11-09T15:00:00Z"));

        getJson("/api/v1/activities/" + id + "/streams")
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.sampleCount").value(3))
                .andExpect(jsonPath("$.streams.ALTITUDE[0]").value(1_500.0))
                .andExpect(jsonPath("$.streams.ALTITUDE[1]").isEmpty())
                .andExpect(jsonPath("$.streams.TIME[2]").value(2_000.0));
        getJson("/api/v1/activities/" + id + "/streams?types=altitude, time")
                .andExpect(jsonPath("$.streams.ALTITUDE").exists())
                .andExpect(jsonPath("$.streams.LATITUDE").doesNotExist());
        getJson("/api/v1/activities/" + id + "/streams?types=GRAVEDAD").andExpect(status().isBadRequest());
    }

    @Test
    void lapsAreListed() throws Exception {
        var id = activity(Instant.parse("2026-11-09T15:00:00Z"));

        getJson("/api/v1/activities/" + id + "/laps")
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.items[0].lapNumber").value(1))
                .andExpect(jsonPath("$.items[0].averageHeartRateBpm").value(120))
                .andExpect(jsonPath("$.items[0].averagePowerWatts").isEmpty());
    }

    @Test
    void anotherUsersActivityDoesNotExist() throws Exception {
        var other = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (:id, 'otro@example.com', 'x')")
                .param("id", other)
                .update();
        var mine = activity(Instant.parse("2026-11-09T15:00:00Z"));
        jdbc.sql("UPDATE activities SET user_id = :other WHERE id = :id")
                .param("other", other)
                .param("id", mine)
                .update();

        for (var path : List.of("", "/streams", "/laps")) {
            getJson("/api/v1/activities/" + mine + path)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("La actividad no existe."));
        }
        mvc.perform(session.authorize(delete("/api/v1/activities/" + mine))).andExpect(status().isNotFound());
        assertThat(ids(getJson("/api/v1/activities").andReturn().getResponse().getContentAsString()))
                .isEmpty();
    }

    @Test
    void deleteRemovesEverythingIncludingTheOriginalAndAllowsReimporting() throws Exception {
        byte[] gpx;
        try (var in = Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/gpx/no-elevation.gpx"))) {
            gpx = in.readAllBytes();
        }
        var accepted = mvc.perform(multipart("/api/v1/activities/imports")
                        .file(new MockMultipartFile("file", "a.gpx", null, gpx))
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String jobId = JsonPath.read(accepted, "$.jobId");
        worker.drain();
        var activityId = jdbc.sql("SELECT activity_id FROM import_jobs WHERE id = CAST(:id AS uuid)")
                .param("id", jobId)
                .query(UUID.class)
                .single();
        var original = Path.of("build", "test-originals")
                .resolve(jdbc.sql("SELECT storage_key FROM activity_files WHERE activity_id = :id")
                        .param("id", activityId)
                        .query(String.class)
                        .single());
        assertThat(Files.exists(original)).isTrue();

        mvc.perform(session.authorize(delete("/api/v1/activities/" + activityId)))
                .andExpect(status().isNoContent())
                .andExpect(openApi().isValid(CONTRACT));

        getJson("/api/v1/activities/" + activityId).andExpect(status().isNotFound());
        assertThat(Files.exists(original))
                .as("el original se borra tras el commit")
                .isFalse();
        for (var table :
                List.of("activity_metrics", "activity_tracks", "activity_streams", "activity_files", "activity_laps")) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + table)
                            .query(Integer.class)
                            .single())
                    .as(table)
                    .isZero();
        }
        assertThat(jdbc.sql("SELECT activity_id FROM import_jobs WHERE id = CAST(:id AS uuid)")
                        .param("id", jobId)
                        .query(UUID.class)
                        .optional())
                .as("el historial del trabajo se conserva sin la referencia")
                .isEmpty();

        // El mismo archivo puede volver a importarse.
        var again = mvc.perform(multipart("/api/v1/activities/imports")
                        .file(new MockMultipartFile("file", "a.gpx", null, gpx))
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        worker.drain();
        getJson("/api/v1/imports/" + JsonPath.read(again, "$.jobId"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}
