package mx.velora.api.activity;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import mx.velora.api.activity.Metric.Source;
import mx.velora.api.activity.NewActivity.MetricValue;
import mx.velora.api.contract.OpenApiContract;
import mx.velora.api.identity.user.OwnerBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class ActivityUpdateIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final AtomicInteger SEED = new AtomicInteger(70);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    @Autowired
    ActivityRegistration registration;

    private TestSessions.Session session;
    private UUID activity;

    @BeforeEach
    void setUp() {
        try {
            session = TestSessions.freshOwner(mvc, jdbc, bootstrap);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var sha = new byte[32];
        Arrays.fill(sha, (byte) SEED.incrementAndGet());
        activity = registration.register(new NewActivity(
                session.userId(),
                null,
                null,
                "Rodada de la mañana",
                ActivitySubType.ROAD,
                Instant.parse("2026-11-08T14:00:00Z"),
                ZoneId.of("America/Mexico_City"),
                "FIT",
                null,
                List.of(
                        new MetricValue(Metric.ELAPSED_TIME, Source.DERIVED, 3_600, "derived-metrics/1", null),
                        new MetricValue(Metric.MOVING_TIME, Source.DERIVED, 3_400, "derived-metrics/1", null)),
                new NewActivity.Samples(1, new int[1], new EnumMap<>(StreamType.class)),
                List.of(),
                new NewActivity.OriginalFile(session.userId() + "/" + UUID.randomUUID(), sha, 1, "velora-fit", "1")));
    }

    private ResultActions patchActivity(UUID id, String body) throws Exception {
        return mvc.perform(session.authorize(patch("/api/v1/activities/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)));
    }

    private UUID bike(String name) throws Exception {
        var json = mvc.perform(session.authorize(post("/api/v1/bikes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"bikeType\":\"GRAVEL\"}")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    @Test
    void correctsTitleTypeAndBikeAndReturnsTheDetail() throws Exception {
        var bike = bike("Grava");

        patchActivity(
                        activity,
                        "{\"title\":\"  Subida al Ajusco \",\"subType\":\"GRAVEL\",\"bikeId\":\"" + bike + "\"}")
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.title").value("Subida al Ajusco"))
                .andExpect(jsonPath("$.subType").value("GRAVEL"))
                .andExpect(jsonPath("$.bikeId").value(bike.toString()))
                .andExpect(jsonPath("$.elapsedTimeSeconds").value(3_600));

        mvc.perform(session.authorize(get("/api/v1/activities?bikeId=" + bike)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(activity.toString()));
    }

    @Test
    void absentFieldsAreKeptAndNullRemovesTheBike() throws Exception {
        var bike = bike("Ruta");
        patchActivity(activity, "{\"bikeId\":\"" + bike + "\"}").andExpect(status().isOk());

        patchActivity(activity, "{\"bikeId\":null}")
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.bikeId").doesNotExist())
                .andExpect(jsonPath("$.title").value("Rodada de la mañana"))
                .andExpect(jsonPath("$.subType").value("ROAD"));
    }

    @Test
    void rejectsEmptyChangesBlankTitlesAndBikesOfOtherUsers() throws Exception {
        patchActivity(activity, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("Indica al menos un cambio; el título y el tipo no pueden quedar vacíos."));
        patchActivity(activity, "{\"title\":\"   \"}").andExpect(status().isBadRequest());
        patchActivity(activity, "{\"subType\":null}").andExpect(status().isBadRequest());
        patchActivity(activity, "{\"title\":\"" + "x".repeat(201) + "\"}").andExpect(status().isBadRequest());
        patchActivity(activity, "{\"bikeId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("La bicicleta no existe."));
    }

    @Test
    void onlyTheOwnerCanEdit() throws Exception {
        patchActivity(UUID.randomUUID(), "{\"title\":\"Otra\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La actividad no existe."));
        mvc.perform(patch("/api/v1/activities/" + activity)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Otra\"}"))
                .andExpect(status().isUnauthorized());
    }
}
