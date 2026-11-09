package mx.velora.api.importing;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import mx.velora.api.IntegrationTest;
import mx.velora.api.TestSessions;
import mx.velora.api.activity.ActivityReprocessed;
import mx.velora.api.contract.OpenApiContract;
import mx.velora.api.identity.user.OwnerBootstrap;
import mx.velora.api.importing.job.ImportWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@RecordApplicationEvents
class ReprocessIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    @Autowired
    ImportWorker worker;

    @Autowired
    ApplicationEvents events;

    private TestSessions.Session session;
    private UUID activityId;

    @BeforeEach
    void importOne() throws Exception {
        session = TestSessions.freshOwner(mvc, jdbc, bootstrap);
        byte[] gpx;
        try (var in =
                Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/gpx/with-time-and-sensors.gpx"))) {
            gpx = in.readAllBytes();
        }
        mvc.perform(multipart("/api/v1/activities/imports")
                .file(new MockMultipartFile("file", "a.gpx", null, gpx))
                .header("Authorization", "Bearer " + session.accessToken()));
        worker.drain();
        activityId = jdbc.sql("SELECT id FROM activities").query(UUID.class).single();
    }

    private String reprocess() throws Exception {
        var json = mvc.perform(session.authorize(post("/api/v1/activities/" + activityId + "/reprocess")))
                .andExpect(status().isAccepted())
                .andExpect(openApi().isValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(json, "$.jobId");
    }

    private String job(String jobId) throws Exception {
        return mvc.perform(session.authorize(get("/api/v1/imports/" + jobId)))
                .andExpect(openApi().isValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private double derivedDistance() {
        return jdbc.sql("SELECT value FROM activity_metrics WHERE activity_id = :id AND metric = 'DISTANCE'")
                .param("id", activityId)
                .query(Double.class)
                .single();
    }

    private Path original() {
        return Path.of("build", "test-originals")
                .resolve(jdbc.sql("SELECT storage_key FROM activity_files WHERE activity_id = :id")
                        .param("id", activityId)
                        .query(String.class)
                        .single());
    }

    @Test
    void recalculatesFromTheOriginalKeepingUserDataAndHistory() throws Exception {
        var expectedDistance = derivedDistance();
        // Simula valores de un algoritmo anterior y un título editado por el usuario.
        jdbc.sql("UPDATE activity_metrics SET value = 1 WHERE activity_id = :id AND metric = 'DISTANCE'")
                .param("id", activityId)
                .update();
        jdbc.sql("UPDATE activities SET distance_meters = 1, title = 'Mi título' WHERE id = :id")
                .param("id", activityId)
                .update();

        var jobId = reprocess();
        worker.drain();

        var job = job(jobId);
        assertThat((String) JsonPath.read(job, "$.kind")).isEqualTo("REPROCESS");
        assertThat((String) JsonPath.read(job, "$.status")).isEqualTo("COMPLETED");
        assertThat((String) JsonPath.read(job, "$.activityId")).isEqualTo(activityId.toString());
        assertThat(derivedDistance()).isEqualTo(expectedDistance);
        var activity = jdbc.sql("SELECT title, distance_meters FROM activities WHERE id = :id")
                .param("id", activityId)
                .query()
                .singleRow();
        assertThat(activity.get("title"))
                .as("los datos del usuario se conservan")
                .isEqualTo("Mi título");
        assertThat(((Number) activity.get("distance_meters")).doubleValue())
                .isEqualTo(expectedDistance, org.assertj.core.data.Offset.offset(0.01));
        assertThat(jdbc.sql("""
                        SELECT value FROM activity_metrics_history
                        WHERE activity_id = :id AND metric = 'DISTANCE' AND source = 'DERIVED'
                        """).param("id", activityId).query(Double.class).single())
                .as("el valor anterior queda para comparar")
                .isEqualTo(1.0);
        assertThat(jdbc.sql("SELECT count(*) FROM activities")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(Files.exists(original())).isTrue();
        assertThat(events.stream(ActivityReprocessed.class)).hasSize(1);
    }

    @Test
    void isIdempotent() throws Exception {
        reprocess();
        worker.drain();
        var first = jdbc.sql(
                        "SELECT metric || source || value FROM activity_metrics WHERE activity_id = :id ORDER BY 1")
                .param("id", activityId)
                .query(String.class)
                .list();
        var streams = jdbc.sql("SELECT count(*) FROM activity_streams WHERE activity_id = :id")
                .param("id", activityId)
                .query(Integer.class)
                .single();

        reprocess();
        worker.drain();

        assertThat(jdbc.sql("SELECT metric || source || value FROM activity_metrics WHERE activity_id = :id ORDER BY 1")
                        .param("id", activityId)
                        .query(String.class)
                        .list())
                .isEqualTo(first);
        assertThat(jdbc.sql("SELECT count(*) FROM activity_streams WHERE activity_id = :id")
                        .param("id", activityId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(streams);
        assertThat(jdbc.sql("SELECT count(*) FROM activity_tracks")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void askingAgainWhilePendingReturnsTheSameJob() throws Exception {
        assertThat(reprocess()).isEqualTo(reprocess());
    }

    @Test
    void unknownOrForeignActivityIsNotFound() throws Exception {
        mvc.perform(session.authorize(post("/api/v1/activities/" + UUID.randomUUID() + "/reprocess")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La actividad no existe."));
    }

    @Test
    void activityDeletedWhileWaitingFailsWithoutRetries() throws Exception {
        var jobId = reprocess();
        mvc.perform(session.authorize(delete("/api/v1/activities/" + activityId)))
                .andExpect(status().isNoContent());

        worker.drain();

        var job = job(jobId);
        assertThat((String) JsonPath.read(job, "$.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(job, "$.errorCode")).isEqualTo("ACTIVITY_NOT_FOUND");
    }

    @Test
    void failureKeepsActivityAndOriginalIntact() throws Exception {
        var before = derivedDistance();
        var original = original();
        var bytes = Files.readAllBytes(original);
        Files.writeString(original, "alterado"); // el SHA-256 ya no coincide
        var jobId = reprocess();
        jdbc.sql("UPDATE import_jobs SET max_attempts = 1 WHERE id = CAST(:id AS uuid)")
                .param("id", jobId)
                .update();
        worker.drain();

        assertThat((String) JsonPath.read(job(jobId), "$.status")).isEqualTo("FAILED");
        assertThat(derivedDistance()).isEqualTo(before);
        assertThat(Files.exists(original))
                .as("un reproceso fallido nunca borra el original")
                .isTrue();
        Files.write(original, bytes);
    }
}
