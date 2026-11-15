package mx.velora.api.integration.strava;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import mx.velora.api.IntegrationTest;
import mx.velora.api.TestSessions;
import mx.velora.api.contract.OpenApiContract;
import mx.velora.api.identity.user.OwnerBootstrap;
import mx.velora.api.importing.job.ImportWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

/** Sincronización con Strava de punta a punta contra {@link FakeStravaApi}: hasta actividades registradas. */
@IntegrationTest
class StravaSyncIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    @Autowired
    FakeStravaApi strava;

    @Autowired
    StravaSync sync;

    @Autowired
    ImportWorker worker;

    private TestSessions.Session session;
    private Instant now;

    @BeforeEach
    void connect() throws Exception {
        session = TestSessions.freshOwner(mvc, jdbc, bootstrap);
        strava.reset();
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var body = mvc.perform(session.authorize(post("/api/v1/integrations/strava/authorization")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var state = UriComponentsBuilder.fromUriString(JsonPath.read(body, "$.authorizationUrl"))
                .build()
                .getQueryParams()
                .getFirst("state");
        mvc.perform(session.authorize(post("/api/v1/integrations/strava/connection"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"bueno\",\"state\":\"" + state
                                + "\",\"scope\":\"read,activity:read_all\"}"))
                .andExpect(status().isOk());
    }

    private Instant daysAgo(int days) {
        return now.minus(Duration.ofDays(days));
    }

    private Map<String, String> stravaActivities() {
        return jdbc
                .sql("SELECT title, sub_type FROM activities WHERE user_id = :u AND source_type = 'STRAVA'")
                .param("u", session.userId())
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<Long, String> outcomes() {
        return jdbc
                .sql("SELECT strava_activity_id, outcome FROM strava_sync_items WHERE user_id = :u")
                .param("u", session.userId())
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getString(2)))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void insertFileActivity(Instant startedAt, double distance) {
        jdbc.sql("""
                        INSERT INTO activities (id, user_id, title, sub_type, started_at, time_zone_at_import,
                            local_start_date, source_type, elapsed_time_seconds, moving_time_seconds, distance_meters,
                            created_at, updated_at)
                        VALUES (:id, :user, 'Del Edge', 'ROAD', :start, 'America/Mexico_City', current_date, 'FIT',
                            600, 590, :distance, now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("user", session.userId())
                .param("start", Timestamp.from(startedAt))
                .param("distance", distance)
                .update();
    }

    @Test
    void backfillImportsCyclingThroughThePipelineAndSkipsTheRest() throws Exception {
        strava.add(1, "Ride", daysAgo(2), false, false, 4200);
        strava.add(2, "MountainBikeRide", daysAgo(3), false, false, 4200);
        strava.add(3, "Run", daysAgo(4), false, false, 4200);
        strava.add(4, "Ride", daysAgo(5), false, true, 4200);
        strava.add(5, "VirtualRide", daysAgo(6), true, false, 4200);
        strava.add(6, "Ride", daysAgo(7), false, false, 4200);
        // La rodada 6 ya se subió como archivo del dispositivo: gana el archivo.
        insertFileActivity(daysAgo(7).plusSeconds(40), 4250);

        var result = sync.syncUser(session.userId());
        worker.drain();

        assertThat(result.submitted()).isEqualTo(3);
        assertThat(result.backfillDone()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(strava.streamCalls).hasValue(3);
        assertThat(stravaActivities())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "Strava 1", "ROAD",
                        "Strava 2", "MOUNTAIN_BIKE",
                        "Strava 5", "INDOOR"));
        assertThat(outcomes())
                .containsEntry(3L, "NOT_CYCLING")
                .containsEntry(4L, "NO_DATA")
                .containsEntry(6L, "DUPLICATE")
                .containsEntry(1L, "SUBMITTED");
        mvc.perform(session.authorize(get("/api/v1/integrations/strava")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.importedActivities").value(3))
                .andExpect(jsonPath("$.backfillDone").value(true))
                .andExpect(jsonPath("$.lastSyncAt").isNotEmpty())
                .andExpect(jsonPath("$.lastSyncError").isEmpty());

        // Una actividad de Strava se ve como cualquier otra, con su procedencia.
        var id = jdbc.sql("SELECT id FROM activities WHERE title = 'Strava 1'")
                .query(UUID.class)
                .single();
        mvc.perform(session.authorize(get("/api/v1/activities/" + id)))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.sourceType").value("STRAVA"))
                .andExpect(jsonPath("$.originalFile.format").value("STRAVA"));
    }

    @Test
    void laterRunsOnlyFetchWhatIsNewAndNeverReimportWhatTheUserDeleted() throws Exception {
        strava.add(1, "Ride", daysAgo(2), false, false, 4200);
        sync.syncUser(session.userId());
        worker.drain();
        var id = jdbc.sql("SELECT id FROM activities WHERE title = 'Strava 1'")
                .query(UUID.class)
                .single();
        mvc.perform(session.authorize(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/activities/" + id)))
                .andExpect(status().is2xxSuccessful());

        // Nuevas rodadas después de la conexión (se simula que la conexión fue hace 10 días).
        jdbc.sql("UPDATE strava_connections SET synced_after = :t WHERE user_id = :u")
                .param("t", Timestamp.from(daysAgo(10)))
                .param("u", session.userId())
                .update();
        strava.add(7, "GravelRide", daysAgo(1), false, false, 4200);
        strava.add(8, "Ride", daysAgo(1).plusSeconds(3600), false, false, 4200);

        var result = sync.syncUser(session.userId());
        worker.drain();

        assertThat(result.submitted()).isEqualTo(2);
        assertThat(strava.streamCalls).as("la rodada 1 no se vuelve a pedir").hasValue(3);
        assertThat(stravaActivities()).containsOnlyKeys("Strava 7", "Strava 8");
        assertThat(jdbc.sql("SELECT synced_after FROM strava_connections WHERE user_id = :u")
                        .param("u", session.userId())
                        .query(Timestamp.class)
                        .single()
                        .toInstant())
                .isEqualTo(daysAgo(1).plusSeconds(3600));
    }

    @Test
    void anExhaustedQuotaKeepsTheProgressForTheNextRun() {
        for (int i = 1; i <= 4; i++) {
            strava.add(i, "Ride", daysAgo(i + 1), false, false, 4200);
        }
        strava.streamsBeforeRateLimit = 2;

        var first = sync.syncUser(session.userId());
        assertThat(first.rateLimited()).isTrue();
        assertThat(first.submitted()).isEqualTo(2);
        assertThat(first.backfillDone()).isFalse();
        assertThat(first.error()).isNull();

        strava.streamsBeforeRateLimit = Integer.MAX_VALUE;
        var second = sync.syncUser(session.userId());
        worker.drain();

        assertThat(second.submitted()).isEqualTo(2);
        assertThat(second.backfillDone()).isTrue();
        assertThat(stravaActivities()).hasSize(4);
    }

    @Test
    void aRevokedAccessIsReportedInTheStatus() throws Exception {
        strava.add(1, "Ride", daysAgo(2), false, false, 4200);
        strava.revoked = true;

        assertThat(sync.syncUser(session.userId()).error()).isEqualTo("AUTH_REVOKED");
        mvc.perform(session.authorize(get("/api/v1/integrations/strava")))
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.lastSyncError").value("AUTH_REVOKED"));
    }

    @Test
    void aStravaActivityIsReprocessedFromItsOwnOriginal() throws Exception {
        strava.add(1, "Ride", daysAgo(2), false, false, 4200);
        sync.syncUser(session.userId());
        worker.drain();
        var id = jdbc.sql("SELECT id FROM activities WHERE title = 'Strava 1'")
                .query(UUID.class)
                .single();

        var accepted = mvc.perform(session.authorize(post("/api/v1/activities/" + id + "/reprocess")))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        worker.drain();

        mvc.perform(session.authorize(get("/api/v1/imports/" + JsonPath.read(accepted, "$.jobId"))))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void aBackfillSinceDateIsNotRequiredAndTheWholeHistoryIsTheDefault() {
        strava.add(1, "Ride", daysAgo(400), false, false, 4200);
        assertThat(sync.syncUser(session.userId()).submitted()).isEqualTo(1);
    }
}
