package mx.velora.api.catalog;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class CatalogIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    private TestSessions.Session session;
    private UUID otherUsersBike;
    private UUID otherUsersDevice;

    @BeforeEach
    void setUp() throws Exception {
        session = TestSessions.freshOwner(mvc, jdbc, bootstrap);
        // Otro usuario creado directamente en BD: no hay registro público y el bootstrap solo admite uno.
        var other = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (:id, 'otro@example.com', 'x')")
                .param("id", other)
                .update();
        otherUsersBike = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO bikes (id, user_id, name, bike_type, created_at, updated_at)
                        VALUES (:id, :user, 'Ajena', 'ROAD', :now, :now)
                        """)
                .param("id", otherUsersBike)
                .param("user", other)
                .param("now", now)
                .update();
        otherUsersDevice = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO devices (id, user_id, display_name, created_at)
                        VALUES (:id, :user, 'Ajeno', :now)
                        """)
                .param("id", otherUsersDevice)
                .param("user", other)
                .param("now", now)
                .update();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(session.authorize(request)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String createBike(String body) throws Exception {
        var json = send(post("/api/v1/bikes"), body)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    @Test
    void createsMinimalBikeWithLocationAndDefaults() throws Exception {
        send(post("/api/v1/bikes"), "{\"name\":\" Rígida \",\"bikeType\":\"MTB\"}")
                .andExpect(status().isCreated())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(header().string("Location", matchesPattern("/api/v1/bikes/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.name").value("Rígida"))
                .andExpect(jsonPath("$.bikeType").value("MTB"))
                .andExpect(jsonPath("$.brand").isEmpty())
                .andExpect(jsonPath("$.weightKg").isEmpty())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void listsOnlyTheUsersBikes() throws Exception {
        createBike("{\"name\":\"Ruta\",\"bikeType\":\"ROAD\",\"brand\":\"Marca\",\"model\":\"M1\",\"weightKg\":8.4}");
        createBike("{\"name\":\"Grava\",\"bikeType\":\"GRAVEL\"}");

        mvc.perform(session.authorize(get("/api/v1/bikes")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].name").value("Ruta"))
                .andExpect(jsonPath("$.items[0].weightKg").value(8.4))
                .andExpect(jsonPath("$.items[1].name").value("Grava"));
    }

    @Test
    void patchChangesOnlySentFieldsAndExplicitNullClearsOptionalOnes() throws Exception {
        var id = createBike("{\"name\":\"Ruta\",\"bikeType\":\"ROAD\",\"brand\":\"Marca\",\"weightKg\":8.4}");

        send(patch("/api/v1/bikes/" + id), "{\"name\":\"Ruta de invierno\"}")
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.name").value("Ruta de invierno"))
                .andExpect(jsonPath("$.brand").value("Marca"))
                .andExpect(jsonPath("$.weightKg").value(8.4));

        send(patch("/api/v1/bikes/" + id), "{\"brand\":null,\"active\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brand").isEmpty())
                .andExpect(jsonPath("$.weightKg").value(8.4))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void rejectsInvalidBikes() throws Exception {
        for (var body : new String[] {
            "{\"name\":\"\",\"bikeType\":\"MTB\"}",
            "{\"name\":\"X\",\"bikeType\":\"BMX\"}",
            "{\"name\":\"X\"}",
            "{\"name\":\"X\",\"bikeType\":\"MTB\",\"weightKg\":0}",
            "{\"name\":\"X\",\"bikeType\":\"MTB\",\"weightKg\":50.01}",
            "{\"name\":\"X\",\"bikeType\":\"MTB\",\"color\":\"rojo\"}",
            "{\"name\":\"" + "x".repeat(101) + "\",\"bikeType\":\"MTB\"}"
        }) {
            send(post("/api/v1/bikes"), body).andExpect(status().isBadRequest());
        }
        mvc.perform(session.authorize(get("/api/v1/bikes"))).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void rejectsEmptyPatchAndClearingRequiredFields() throws Exception {
        var id = createBike("{\"name\":\"Ruta\",\"bikeType\":\"ROAD\"}");

        for (var body : new String[] {"{}", "{\"name\":null}", "{\"bikeType\":null}", "{\"active\":null}"}) {
            send(patch("/api/v1/bikes/" + id), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Solicitud inválida"));
        }
        send(patch("/api/v1/bikes/" + id), "{\"weightKg\":-1}").andExpect(status().isBadRequest());
    }

    @Test
    void anotherUsersBikeIsInvisible() throws Exception {
        send(patch("/api/v1/bikes/" + otherUsersBike), "{\"name\":\"Mía\"}")
                .andExpect(status().isNotFound())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.detail").value("La bicicleta no existe."));
        mvc.perform(session.authorize(delete("/api/v1/bikes/" + otherUsersBike)))
                .andExpect(status().isNotFound());
        mvc.perform(session.authorize(get("/api/v1/bikes"))).andExpect(jsonPath("$.items", hasSize(0)));

        var stillThere = jdbc.sql("SELECT name FROM bikes WHERE id = :id")
                .param("id", otherUsersBike)
                .query(String.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(stillThere).isEqualTo("Ajena");
    }

    @Test
    void deletesBike() throws Exception {
        var id = createBike("{\"name\":\"Vieja\",\"bikeType\":\"OTHER\"}");

        mvc.perform(session.authorize(delete("/api/v1/bikes/" + id)))
                .andExpect(status().isNoContent())
                .andExpect(openApi().isValid(CONTRACT));
        mvc.perform(session.authorize(delete("/api/v1/bikes/" + id))).andExpect(status().isNotFound());
    }

    @Test
    void createsAndListsOnlyTheUsersDevices() throws Exception {
        send(post("/api/v1/devices"), "{\"displayName\":\"Ciclocomputadora\",\"manufacturer\":\"Marca\"}")
                .andExpect(status().isCreated())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(header().string("Location", matchesPattern("/api/v1/devices/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.model").isEmpty());

        mvc.perform(session.authorize(get("/api/v1/devices")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].displayName").value("Ciclocomputadora"));
    }

    @Test
    void rejectsInvalidDevices() throws Exception {
        send(post("/api/v1/devices"), "{\"displayName\":\"  \"}").andExpect(status().isBadRequest());
        send(post("/api/v1/devices"), "{\"manufacturer\":\"Marca\"}").andExpect(status().isBadRequest());
    }

    @Test
    void catalogRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/bikes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/devices")).andExpect(status().isUnauthorized());
    }

    @Test
    void deletingTheUserDeletesItsCatalog() {
        jdbc.sql("DELETE FROM users WHERE email = 'otro@example.com'").update();

        var remaining = jdbc.sql("SELECT count(*) FROM bikes WHERE id = :bike")
                        .param("bike", otherUsersBike)
                        .query(Integer.class)
                        .single()
                + jdbc.sql("SELECT count(*) FROM devices WHERE id = :device")
                        .param("device", otherUsersDevice)
                        .query(Integer.class)
                        .single();
        org.assertj.core.api.Assertions.assertThat(remaining).isZero();
    }
}
