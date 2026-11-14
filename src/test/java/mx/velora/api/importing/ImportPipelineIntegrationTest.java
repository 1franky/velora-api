package mx.velora.api.importing;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.garmin.fit.Sport;
import com.garmin.fit.SubSport;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import mx.velora.api.IntegrationTest;
import mx.velora.api.TestSessions;
import mx.velora.api.activity.ActivityImported;
import mx.velora.api.contract.OpenApiContract;
import mx.velora.api.identity.user.OwnerBootstrap;
import mx.velora.api.importing.job.ImportWorker;
import mx.velora.api.importing.parsing.FitFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@RecordApplicationEvents
@ExtendWith(OutputCaptureExtension.class)
class ImportPipelineIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final Path ORIGINALS = Path.of("build", "test-originals");

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

    @Autowired
    MeterRegistry meters;

    private TestSessions.Session session;

    @BeforeEach
    void login() throws Exception {
        session = TestSessions.freshOwner(mvc, jdbc, bootstrap);
    }

    private static byte[] fixture(String path) throws IOException {
        try (var in =
                Objects.requireNonNull(ImportPipelineIntegrationTest.class.getResourceAsStream("/fixtures/" + path))) {
            return in.readAllBytes();
        }
    }

    private ResultActions upload(byte[] content, String fileName, Map<String, String> params) throws Exception {
        var request = multipart("/api/v1/activities/imports")
                .file(new MockMultipartFile("file", fileName, MediaType.APPLICATION_OCTET_STREAM_VALUE, content));
        params.forEach(request::param);
        return mvc.perform(request.header("Authorization", "Bearer " + session.accessToken()));
    }

    /** Sube, procesa la cola y devuelve el JSON del trabajo. */
    private String importAndProcess(byte[] content, String fileName, Map<String, String> params) throws Exception {
        var accepted = upload(content, fileName, params)
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        worker.drain();
        return job(JsonPath.read(accepted, "$.jobId"));
    }

    private String job(String jobId) throws Exception {
        return mvc.perform(session.authorize(get("/api/v1/imports/" + jobId)))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private Map<String, Object> activity(String activityId) {
        return jdbc.sql("""
                        SELECT title, sub_type, local_start_date::text AS local_date, moving_time_seconds,
                               metrics_provenance ->> 'movingTimeSeconds' AS moving_source, bike_id
                        FROM activities WHERE id = CAST(:id AS uuid)
                        """).param("id", activityId).query().singleRow();
    }

    private String storageKeyOf(String jobId) {
        return jdbc.sql("SELECT storage_key FROM import_jobs WHERE id = CAST(:id AS uuid)")
                .param("id", jobId)
                .query(String.class)
                .single();
    }

    private String createBike(String type) throws Exception {
        var json = mvc.perform(session.authorize(post("/api/v1/bikes"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bici\",\"bikeType\":\"" + type + "\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    @Test
    void gpxImportIsAcceptedProcessedAndQueryable() throws Exception {
        var accepted = upload(fixture("gpx/with-time-and-sensors.gpx"), "C:\\rutas\\mi rodada.gpx", Map.of())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        // El validador no lee cuerpos multipart de MockMvc: se valida solo la respuesta.
        var report = CONTRACT.validateResponse(
                "/api/v1/activities/imports",
                com.atlassian.oai.validator.model.Request.Method.POST,
                com.atlassian.oai.validator.model.SimpleResponse.Builder.status(202)
                        .withContentType("application/json")
                        .withBody(accepted)
                        .build());
        assertThat(report.getMessages()).isEmpty();
        String jobId = JsonPath.read(accepted, "$.jobId");
        assertThat((String) JsonPath.read(accepted, "$.statusUrl")).isEqualTo("/api/v1/imports/" + jobId);
        assertThat(jdbc.sql("SELECT file_name FROM import_jobs")
                        .query(String.class)
                        .single())
                .as("solo el nombre, sin la ruta del cliente")
                .isEqualTo("mi rodada.gpx");

        assertThat(worker.drain()).isEqualTo(1);

        var job = job(jobId);
        assertThat((String) JsonPath.read(job, "$.status")).isEqualTo("COMPLETED");
        String activityId = JsonPath.read(job, "$.activityId");
        var activity = activity(activityId);
        assertThat(activity.get("title")).isEqualTo("Rodada de prueba");
        assertThat(activity.get("sub_type")).isEqualTo("OTHER");
        assertThat(activity.get("moving_source"))
                .as("GPX no trae datos del dispositivo")
                .isEqualTo("DERIVED");
        assertThat(events.stream(ActivityImported.class)).hasSize(1);
        assertThat(Files.exists(ORIGINALS.resolve(storageKeyOf(jobId)))).isTrue();
    }

    @Test
    void sameFileTwiceIsDuplicateAndItsCopyIsDiscarded() throws Exception {
        var gpx = fixture("gpx/no-elevation.gpx");
        String first = JsonPath.read(importAndProcess(gpx, "a.gpx", Map.of()), "$.activityId");

        var accepted =
                upload(gpx, "copia.gpx", Map.of()).andReturn().getResponse().getContentAsString();
        String secondJob = JsonPath.read(accepted, "$.jobId");
        worker.drain();

        var job = job(secondJob);
        assertThat((String) JsonPath.read(job, "$.status")).isEqualTo("DUPLICATE");
        assertThat((String) JsonPath.read(job, "$.activityId")).isEqualTo(first);
        assertThat(Files.exists(ORIGINALS.resolve(storageKeyOf(secondJob)))).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM activities")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void fitUsesDeviceMetricsAndSubSportOverBikeType() throws Exception {
        var bike = createBike("MTB");
        var fit = FitFixtures.activity()
                .timerStart(0)
                .record(0, r -> {
                    r.setPositionLat(FitFixtures.semicircles(19.0));
                    r.setPositionLong(FitFixtures.semicircles(-99.0));
                })
                .record(10, r -> {
                    r.setPositionLat(FitFixtures.semicircles(19.0005));
                    r.setPositionLong(FitFixtures.semicircles(-99.0));
                })
                .session(s -> {
                    s.setSport(Sport.CYCLING);
                    s.setSubSport(SubSport.ROAD);
                    s.setTotalTimerTime(9f);
                })
                .build();

        String activityId = JsonPath.read(importAndProcess(fit, "r.fit", Map.of("bikeId", bike)), "$.activityId");

        var activity = activity(activityId);
        assertThat(activity.get("sub_type")).isEqualTo("ROAD");
        assertThat(((Number) activity.get("moving_time_seconds")).doubleValue()).isEqualTo(9.0);
        assertThat(activity.get("moving_source")).isEqualTo("DEVICE_REPORTED");
        assertThat(activity.get("bike_id").toString()).isEqualTo(bike);
    }

    @Test
    void bikeTypeDecidesSubTypeWhenTheFileDoesNot() throws Exception {
        var bike = createBike("GRAVEL");

        String activityId = JsonPath.read(
                importAndProcess(fixture("gpx/no-elevation.gpx"), "g.gpx", Map.of("bikeId", bike)), "$.activityId");

        assertThat(activity(activityId).get("sub_type")).isEqualTo("GRAVEL");
    }

    @Test
    void sameFitActivityReexportedIsDuplicateByFileId() throws Exception {
        String first = JsonPath.read(
                importAndProcess(FitFixtures.activity().record(0).record(1).build(), "a.fit", Map.of()),
                "$.activityId");

        var reexported = FitFixtures.activity().record(0).record(1).record(2).build(); // otro archivo, mismo file_id
        var job = importAndProcess(reexported, "b.fit", Map.of());

        assertThat((String) JsonPath.read(job, "$.status")).isEqualTo("DUPLICATE");
        assertThat((String) JsonPath.read(job, "$.activityId")).isEqualTo(first);
    }

    @Test
    void similarTcxIsImportedWithAPossibleDuplicateWarning() throws Exception {
        var tcx = fixture("tcx/with-laps.tcx");
        String first = JsonPath.read(importAndProcess(tcx, "a.tcx", Map.of()), "$.activityId");

        var modified = (new String(tcx, StandardCharsets.UTF_8) + "\n<!-- otra exportación -->\n")
                .getBytes(StandardCharsets.UTF_8);
        var job = importAndProcess(modified, "b.tcx", Map.of());

        assertThat((String) JsonPath.read(job, "$.status")).isEqualTo("COMPLETED");
        assertThat((java.util.List<String>) JsonPath.read(job, "$.possibleDuplicateActivityIds"))
                .containsExactly(first);
        assertThat((java.util.List<String>) JsonPath.read(job, "$.warnings[*].code"))
                .contains("POSSIBLE_DUPLICATE");
    }

    private double jobs(String outcome) {
        var counter = meters.find("velora.import.jobs")
                .tags("kind", "import", "outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private double queued() {
        return meters.get("velora.import.queue").tag("status", "queued").gauge().value();
    }

    @Test
    void metricsCountOutcomesDurationAndQueue(CapturedOutput output) throws Exception {
        var completed = jobs("completed");
        var duplicate = jobs("duplicate");
        var rejected = jobs("rejected");
        var gpx = fixture("gpx/no-elevation.gpx");

        String jobId = JsonPath.read(
                upload(gpx, "a.gpx", Map.of()).andReturn().getResponse().getContentAsString(), "$.jobId");
        upload(gpx, "copia.gpx", Map.of());
        upload(fixture("gpx/truncated.gpx"), "t.gpx", Map.of());
        assertThat(queued()).isEqualTo(3);

        worker.drain();

        assertThat(queued()).isZero();
        assertThat(jobs("completed") - completed).isEqualTo(1);
        assertThat(jobs("duplicate") - duplicate).isEqualTo(1);
        assertThat(jobs("rejected") - rejected).isEqualTo(1);
        assertThat(meters.get("velora.import.duration")
                        .tags("kind", "import", "outcome", "completed")
                        .timer()
                        .count())
                .isPositive();
        // Cada línea de log del intento lleva el id del trabajo.
        assertThat(output.getAll()).contains("[" + jobId + "]");
    }

    @Test
    void invalidFilesAreRejectedWithSpanishDetailAndDiscarded() throws Exception {
        var truncated = upload(fixture("gpx/truncated.gpx"), "t.gpx", Map.of())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String jobId = JsonPath.read(truncated, "$.jobId");
        worker.drain();

        var job = job(jobId);
        assertThat((String) JsonPath.read(job, "$.status")).isEqualTo("REJECTED");
        assertThat((String) JsonPath.read(job, "$.errorCode")).isEqualTo("MALFORMED_FILE");
        assertThat((String) JsonPath.read(job, "$.errorDetail")).startsWith("XML inválido cerca de la línea");
        assertThat(Files.exists(ORIGINALS.resolve(storageKeyOf(jobId)))).isFalse();

        assertThat((String) JsonPath.read(
                        importAndProcess(
                                "no soy un archivo de actividad".getBytes(StandardCharsets.UTF_8), "x.txt", Map.of()),
                        "$.errorCode"))
                .isEqualTo("UNSUPPORTED_FORMAT");
        assertThat((String)
                        JsonPath.read(importAndProcess(fixture("gpx/no-time.gpx"), "n.gpx", Map.of()), "$.errorCode"))
                .isEqualTo("INVALID_DATA");
    }

    @Test
    void titleOverrideAndUserTimeZoneAreApplied() throws Exception {
        mvc.perform(session.authorize(patch("/api/v1/me/preferences"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"timeZone\":\"Asia/Tokyo\"}"));

        String activityId = JsonPath.read(
                importAndProcess(fixture("gpx/midnight-crossing.gpx"), "m.gpx", Map.of("activityTitle", " Nocturna ")),
                "$.activityId");

        var activity = activity(activityId);
        assertThat(activity.get("title")).isEqualTo("Nocturna");
        assertThat(activity.get("local_date"))
                .as("05:55Z = 14:55 del 09/11 en Tokio")
                .isEqualTo("2026-11-09");
    }

    @Test
    void jobLeftProcessingByARestartIsRecovered() throws Exception {
        var accepted = upload(fixture("gpx/no-elevation.gpx"), "r.gpx", Map.of())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String jobId = JsonPath.read(accepted, "$.jobId");
        // Simula un proceso que tomó el trabajo y cayó: PROCESSING con el bloqueo vencido.
        jdbc.sql("""
                        UPDATE import_jobs SET status = 'PROCESSING', stage = 'VALIDATING', attempt_count = 1,
                            locked_until = now() - interval '1 minute'
                        WHERE id = CAST(:id AS uuid)
                        """).param("id", jobId).update();

        worker.drain();

        assertThat((String) JsonPath.read(job(jobId), "$.status")).isEqualTo("COMPLETED");
    }

    @Test
    void retryAfterACrashFollowingRegistrationCompletesInsteadOfDuplicating() throws Exception {
        var job = importAndProcess(fixture("gpx/no-elevation.gpx"), "i.gpx", Map.of());
        String activityId = JsonPath.read(job, "$.activityId");
        String jobId = JsonPath.read(job, "$.jobId");
        // Simula que la actividad se registró pero el proceso cayó antes de marcar el trabajo.
        jdbc.sql("""
                        UPDATE import_jobs SET status = 'PROCESSING', activity_id = NULL, attempt_count = 1,
                            locked_until = now() - interval '1 minute'
                        WHERE id = CAST(:id AS uuid)
                        """).param("id", jobId).update();

        worker.drain();

        var retried = job(jobId);
        assertThat((String) JsonPath.read(retried, "$.status")).isEqualTo("COMPLETED");
        assertThat((String) JsonPath.read(retried, "$.activityId")).isEqualTo(activityId);
        assertThat(Files.exists(ORIGINALS.resolve(storageKeyOf(jobId))))
                .as("el original se conserva")
                .isTrue();
    }

    @Test
    void technicalFailuresAreRetriedAndThenFail() throws Exception {
        var accepted = upload(fixture("gpx/no-elevation.gpx"), "f.gpx", Map.of())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String jobId = JsonPath.read(accepted, "$.jobId");
        Files.delete(ORIGINALS.resolve(storageKeyOf(jobId))); // fallo técnico: el original no está

        worker.drain();
        var row = jdbc.sql("SELECT status, stage, attempt_count, next_attempt_at > now() AS later FROM import_jobs")
                .query()
                .singleRow();
        assertThat(row).containsEntry("status", "QUEUED").containsEntry("stage", "FAILED_RETRYABLE");
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("later")).isEqualTo(true);

        jdbc.sql("UPDATE import_jobs SET attempt_count = 2, next_attempt_at = now()")
                .update();
        worker.drain();

        var failed = job(jobId);
        assertThat((String) JsonPath.read(failed, "$.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(failed, "$.errorCode")).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void uploadValidation() throws Exception {
        upload(new byte[0], "vacio.gpx", Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Archivo vacío"));
        upload(
                        fixture("gpx/no-elevation.gpx"),
                        "g.gpx",
                        Map.of("bikeId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La bicicleta o el dispositivo indicado no existe."));
        upload(fixture("gpx/no-elevation.gpx"), "g.gpx", Map.of("activityTitle", "x".repeat(201)))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/activities/imports")
                        .file(new MockMultipartFile("file", "g.gpx", null, fixture("gpx/no-elevation.gpx"))))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT count(*) FROM import_jobs")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void filesOverTheLimitAreRejectedWith413() throws Exception {
        upload(new byte[50 * 1024 * 1024 + 1], "enorme.fit", Map.of())
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.title").value("Archivo demasiado grande"));
    }

    @Test
    void anotherUsersJobIsInvisible() throws Exception {
        var other = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (:id, 'otro@example.com', 'x')")
                .param("id", other)
                .update();
        var jobId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO import_jobs (id, user_id, status, stage, file_name, storage_key, sha256, file_size_bytes,
                                                 next_attempt_at, created_at, updated_at)
                        VALUES (:id, :user, 'QUEUED', 'STORED', 'x', 'k', :sha, 1, now(), now(), now())
                        """)
                .param("id", jobId)
                .param("user", other)
                .param("sha", new byte[32])
                .update();

        mvc.perform(session.authorize(get("/api/v1/imports/" + jobId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La importación no existe."));
    }
}
