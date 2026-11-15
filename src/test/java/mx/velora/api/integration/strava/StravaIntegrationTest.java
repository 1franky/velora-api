package mx.velora.api.integration.strava;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import mx.velora.api.IntegrationTest;
import mx.velora.api.TestSessions;
import mx.velora.api.contract.OpenApiContract;
import mx.velora.api.identity.user.OwnerBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

/** Conexión con Strava contra {@link FakeStravaAuth} (ADR-030). */
@IntegrationTest
class StravaIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final String SCOPE = "read,activity:read_all";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    @Autowired
    FakeStravaAuth strava;

    @Autowired
    StravaConnections connections;

    private TestSessions.Session session;

    @BeforeEach
    void login() throws Exception {
        session = TestSessions.freshOwner(mvc, jdbc, bootstrap);
        strava.deauthorized.clear();
    }

    private String authorize() throws Exception {
        var body = mvc.perform(session.authorize(post("/api/v1/integrations/strava/authorization")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String url = JsonPath.read(body, "$.authorizationUrl");
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");
    }

    private ResultActions connect(String code, String state, String scope) throws Exception {
        return mvc.perform(session.authorize(post("/api/v1/integrations/strava/connection"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"state\":\"" + state + "\",\"scope\":\"" + scope + "\"}"));
    }

    private void insertActivity(String sourceType) {
        jdbc.sql("""
                        INSERT INTO activities (id, user_id, title, sub_type, started_at, time_zone_at_import,
                            local_start_date, source_type, elapsed_time_seconds, moving_time_seconds,
                            created_at, updated_at)
                        VALUES (:id, :user, 'Rodada', 'ROAD', now(), 'America/Mexico_City', current_date, :source,
                            3600, 3500, now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("user", session.userId())
                .param("source", sourceType)
                .update();
    }

    @Test
    void statusBeforeConnecting() throws Exception {
        mvc.perform(session.authorize(get("/api/v1/integrations/strava")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.athleteId").isEmpty())
                .andExpect(jsonPath("$.importedActivities").value(0));
    }

    @Test
    void authorizationUrlAsksForAllActivitiesWithAOneTimeState() throws Exception {
        var body = mvc.perform(session.authorize(post("/api/v1/integrations/strava/authorization")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var params = UriComponentsBuilder.fromUriString(JsonPath.read(body, "$.authorizationUrl"))
                .build()
                .getQueryParams();
        assertThat(params.getFirst("client_id")).isEqualTo("velora-pruebas");
        assertThat(params.getFirst("response_type")).isEqualTo("code");
        assertThat(params.getFirst("scope")).isEqualTo("read,activity:read_all");
        assertThat(params.getFirst("redirect_uri")).contains("velora.local");
        assertThat(params.getFirst("state")).hasSizeGreaterThanOrEqualTo(40);
    }

    @Test
    void connectStoresEncryptedTokensAndTheStateWorksOnlyOnce() throws Exception {
        var state = authorize();

        connect("bueno", state, SCOPE)
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.athleteId").value(FakeStravaAuth.ATHLETE));

        var stored = jdbc.sql("SELECT access_token, refresh_token FROM strava_connections WHERE user_id = :u")
                .param("u", session.userId())
                .query((rs, n) -> new String(rs.getBytes(1), StandardCharsets.ISO_8859_1)
                        + new String(rs.getBytes(2), StandardCharsets.ISO_8859_1))
                .single();
        assertThat(stored).doesNotContain("acceso-bueno").doesNotContain("refresh-acceso-bueno");
        assertThat(connections.accessToken(session.userId())).isEqualTo("acceso-bueno");

        connect("bueno", state, SCOPE)
                .andExpect(status().isBadRequest())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.title").value("Autorización no válida"));
        connect("bueno", "inventado-123456789", SCOPE).andExpect(status().isBadRequest());
    }

    @Test
    void connectRequiresReadingAllActivities() throws Exception {
        connect("bueno", authorize(), "read")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Falta un permiso"));
    }

    @Test
    void stravaFailuresAreReported() throws Exception {
        connect("rechazado", authorize(), SCOPE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Autorización no válida"));
        connect("caido", authorize(), SCOPE)
                .andExpect(status().isBadGateway())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.title").value("Strava no responde"));
        mvc.perform(session.authorize(get("/api/v1/integrations/strava")))
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void anAthleteCanOnlyBeLinkedToOneUser() throws Exception {
        var other = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, 'x')")
                .param("id", other)
                .param("email", "otra-" + other + "@example.com")
                .update();
        jdbc.sql("""
                        INSERT INTO strava_connections (user_id, athlete_id, scope, access_token, refresh_token,
                            access_expires_at, connected_at, updated_at)
                        VALUES (:id, :athlete, 'read', '\\x00', '\\x00', now(), now(), now())
                        """)
                .param("id", other)
                .param("athlete", FakeStravaAuth.OTHER_ATHLETE)
                .update();

        connect("otro-atleta", authorize(), SCOPE)
                .andExpect(status().isConflict())
                .andExpect(openApi().isValid(CONTRACT));
    }

    @Test
    void anExpiringAccessTokenIsRefreshedAndStoredEncrypted() throws Exception {
        connect("bueno", authorize(), SCOPE).andExpect(status().isOk());
        jdbc.sql("UPDATE strava_connections SET access_expires_at = :t WHERE user_id = :u")
                .param("t", Timestamp.from(Instant.now().plusSeconds(60)))
                .param("u", session.userId())
                .update();

        var token = connections.accessToken(session.userId());

        assertThat(token).startsWith("acceso-renovado-");
        assertThat(connections.accessToken(session.userId()))
                .as("ya no hace falta renovar")
                .isEqualTo(token);
    }

    @Test
    void disconnectRevokesAndOptionallyDeletesOnlyStravaActivities() throws Exception {
        connect("bueno", authorize(), SCOPE).andExpect(status().isOk());
        insertActivity("STRAVA");
        insertActivity("STRAVA");
        insertActivity("FIT");
        mvc.perform(session.authorize(get("/api/v1/integrations/strava")))
                .andExpect(jsonPath("$.importedActivities").value(2));

        mvc.perform(session.authorize(delete("/api/v1/integrations/strava/connection"))
                        .param("deleteActivities", "true"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.deletedActivities").value(2));

        assertThat(strava.deauthorized).containsExactly("acceso-bueno");
        assertThat(jdbc.sql("SELECT source_type FROM activities WHERE user_id = :u")
                        .param("u", session.userId())
                        .query(String.class)
                        .list())
                .containsExactly("FIT");
        mvc.perform(session.authorize(get("/api/v1/integrations/strava")))
                .andExpect(jsonPath("$.connected").value(false));

        // Idempotente.
        mvc.perform(session.authorize(delete("/api/v1/integrations/strava/connection")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedActivities").value(0));
    }

    @Test
    void requiresASession() throws Exception {
        mvc.perform(get("/api/v1/integrations/strava")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/integrations/strava/authorization")).andExpect(status().isUnauthorized());
    }
}
