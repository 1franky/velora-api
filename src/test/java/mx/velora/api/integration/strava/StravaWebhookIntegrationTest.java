package mx.velora.api.integration.strava;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;

/** Webhooks de Strava de punta a punta (ADR-030, fase 3). */
@IntegrationTest
class StravaWebhookIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final long SUBSCRIPTION = 777L;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    @Autowired
    FakeStravaApi strava;

    @Autowired
    StravaWebhooks webhooks;

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
        jdbc.sql("DELETE FROM strava_webhook_events").update();
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        StravaTestSupport.connect(mvc, session);
        webhooks.useSubscription(SUBSCRIPTION);
    }

    private ResultActions event(
            long subscription, long owner, String objectType, long objectId, String aspect, String updates)
            throws Exception {
        return mvc.perform(post("/api/v1/integrations/strava/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"aspect_type":"%s","event_time":1763000000,"object_id":%d,"object_type":"%s",
                         "owner_id":%d,"subscription_id":%d,"updates":%s,"campo_nuevo":true}
                        """.formatted(aspect, objectId, objectType, owner, subscription, updates)));
    }

    private ResultActions activityEvent(long objectId, String aspect, String updates) throws Exception {
        return event(SUBSCRIPTION, FakeStravaAuth.ATHLETE, "activity", objectId, aspect, updates);
    }

    private long pending() {
        return jdbc.sql("SELECT count(*) FROM strava_webhook_events WHERE processed_at IS NULL")
                .query(Long.class)
                .single();
    }

    private List<String> stravaTitles() {
        return jdbc.sql("SELECT title FROM activities WHERE user_id = :u AND source_type = 'STRAVA' ORDER BY title")
                .param("u", session.userId())
                .query(String.class)
                .list();
    }

    private void importFromStrava(long id) {
        strava.add(id, "Ride", now.minus(Duration.ofDays(2)).plusSeconds(id), false, false, 4200);
        sync.syncUser(session.userId());
        worker.drain();
    }

    @Test
    void stravaValidatesTheSubscriptionWithTheVerifyTokenAndNoSession() throws Exception {
        mvc.perform(get("/api/v1/integrations/strava/webhook")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", webhooks.verifyToken())
                        .param("hub.challenge", "reto-15f7"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$['hub.challenge']").value("reto-15f7"));
        mvc.perform(get("/api/v1/integrations/strava/webhook")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "inventado")
                        .param("hub.challenge", "reto"))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyEventsFromTheOwnSubscriptionAndConnectedAthletesAreKept() throws Exception {
        event(999L, FakeStravaAuth.ATHLETE, "activity", 1, "create", "{}")
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT));
        event(SUBSCRIPTION, 55L, "activity", 1, "create", "{}").andExpect(status().isOk());
        assertThat(pending()).isZero();

        activityEvent(1, "create", "{}").andExpect(status().isOk());
        assertThat(pending()).isEqualTo(1);
    }

    @Test
    void aNewActivityTriggersASync() throws Exception {
        strava.add(41, "Ride", now.minus(Duration.ofHours(1)), false, false, 4200);
        jdbc.sql("UPDATE strava_connections SET synced_after = now() - interval '1 day', backfill_done = true")
                .update();
        activityEvent(41, "create", "{}");

        webhooks.processPending();
        worker.drain();

        assertThat(stravaTitles()).containsExactly("Strava 41");
        assertThat(pending()).isZero();
    }

    @Test
    void aRenameInStravaRenamesTheActivity() throws Exception {
        importFromStrava(42);
        activityEvent(42, "update", "{\"title\":\"Nuevo nombre\"}");

        webhooks.processPending();

        assertThat(stravaTitles()).containsExactly("Nuevo nombre");
    }

    @Test
    void aDeletionInStravaDeletesTheActivityAndItIsNeverImportedAgain() throws Exception {
        importFromStrava(43);
        activityEvent(43, "delete", "{}");

        webhooks.processPending();
        sync.syncUser(session.userId());
        worker.drain();

        assertThat(stravaTitles()).isEmpty();
        assertThat(jdbc.sql("SELECT outcome FROM strava_sync_items WHERE strava_activity_id = 43")
                        .query(String.class)
                        .single())
                .isEqualTo("DELETED");
    }

    @Test
    void aDeletionArrivingBeforeTheImportFinishesWaitsForIt() throws Exception {
        strava.add(44, "Ride", now.minus(Duration.ofDays(2)), false, false, 4200);
        sync.syncUser(session.userId());
        activityEvent(44, "delete", "{}");

        webhooks.processPending();
        assertThat(pending()).as("la importación aún no termina").isEqualTo(1);

        worker.drain();
        assertThat(stravaTitles()).containsExactly("Strava 44");
        webhooks.processPending();

        assertThat(stravaTitles()).isEmpty();
        assertThat(pending()).isZero();
    }

    @Test
    void revokingAccessInStravaForgetsTheConnectionButKeepsTheActivities() throws Exception {
        importFromStrava(45);
        event(
                SUBSCRIPTION,
                FakeStravaAuth.ATHLETE,
                "athlete",
                FakeStravaAuth.ATHLETE,
                "update",
                "{\"authorized\":\"false\"}");

        webhooks.processPending();

        mvc.perform(session.authorize(get("/api/v1/integrations/strava")))
                .andExpect(jsonPath("$.connected").value(false));
        assertThat(stravaTitles()).containsExactly("Strava 45");
    }
}
