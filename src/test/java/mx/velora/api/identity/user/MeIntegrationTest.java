package mx.velora.api.identity.user;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import mx.velora.api.IntegrationTest;
import mx.velora.api.TestSessions;
import mx.velora.api.contract.OpenApiContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class MeIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    private TestSessions.Session session;

    @BeforeEach
    void login() throws Exception {
        session = TestSessions.freshOwner(mvc, jdbc, bootstrap);
    }

    private ResultActions patchPreferences(String body) throws Exception {
        return mvc.perform(session.authorize(patch("/api/v1/me/preferences"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void returnsProfileWithMexicanDefaults() throws Exception {
        mvc.perform(session.authorize(get("/api/v1/me")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.id").value(session.userId().toString()))
                .andExpect(jsonPath("$.email").value(session.email()))
                .andExpect(jsonPath("$.preferences.locale").value("es-MX"))
                .andExpect(jsonPath("$.preferences.timeZone").value("America/Mexico_City"))
                .andExpect(jsonPath("$.preferences.distanceUnit").value("km"))
                .andExpect(jsonPath("$.preferences.elevationUnit").value("m"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(openApi().isValid(CONTRACT));
    }

    @Test
    void updatesOnlyTheTimeZone() throws Exception {
        patchPreferences("{\"timeZone\":\"America/Tijuana\"}")
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.timeZone").value("America/Tijuana"))
                .andExpect(jsonPath("$.distanceUnit").value("km"));

        mvc.perform(session.authorize(get("/api/v1/me")))
                .andExpect(jsonPath("$.preferences.timeZone").value("America/Tijuana"))
                .andExpect(jsonPath("$.preferences.elevationUnit").value("m"));
    }

    @Test
    void rejectsUnknownOrFixedOffsetTimeZones() throws Exception {
        for (var zone : new String[] {"Marte/Olympus", "+05:00", "GMT+3", "america/mexico_city"}) {
            patchPreferences("{\"timeZone\":\"" + zone + "\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Zona horaria no válida"));
        }
        mvc.perform(session.authorize(get("/api/v1/me")))
                .andExpect(jsonPath("$.preferences.timeZone").value("America/Mexico_City"));
    }

    @Test
    void rejectsUnsupportedUnits() throws Exception {
        patchPreferences("{\"distanceUnit\":\"mi\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Por ahora solo se admiten kilómetros (km) y metros (m)."));
    }

    @Test
    void rejectsEmptyUpdate() throws Exception {
        patchPreferences("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Indica al menos una preferencia para actualizar."));
    }

    @Test
    void rejectsUnknownProperties() throws Exception {
        patchPreferences("{\"timeZone\":\"America/Cancun\",\"locale\":\"en-US\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mvc.perform(session.authorize(get("/api/v1/me")))
                .andExpect(jsonPath("$.preferences.timeZone").value("America/Mexico_City"))
                .andExpect(jsonPath("$.preferences.locale").value("es-MX"));
    }
}
