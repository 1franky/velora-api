package mx.velora.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class VeloraApiApplicationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Test
    void flywayEnablesPostgis() {
        var version = jdbc.queryForObject("SELECT postgis_lib_version()", String.class);
        assertThat(version).startsWith("3.");

        var applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void healthAndProbesAreUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    void unauthenticatedRequestToAnyApiRouteReturnsSpanishProblem() throws Exception {
        // Sin token no se revela si la ruta existe: 401 antes que 404.
        mvc.perform(get("/api/v1/no-existe"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("No autenticado"))
                .andExpect(jsonPath("$.detail").value("Inicia sesión para continuar."));
    }
}
