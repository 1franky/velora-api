package mx.velora.api.account;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;
import mx.velora.api.IntegrationTest;
import mx.velora.api.TestSessions;
import mx.velora.api.activity.ActivityRegistration;
import mx.velora.api.activity.ActivitySubType;
import mx.velora.api.activity.Metric;
import mx.velora.api.activity.Metric.Source;
import mx.velora.api.activity.NewActivity;
import mx.velora.api.activity.NewActivity.MetricValue;
import mx.velora.api.activity.StreamType;
import mx.velora.api.contract.OpenApiContract;
import mx.velora.api.identity.user.OwnerBootstrap;
import mx.velora.api.platform.storage.OriginalFileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Exportación y borrado total de la cuenta (ADR-028). */
@IntegrationTest
class AccountIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final Path ORIGINALS = Path.of("build/test-originals");
    private static final byte[] ORIGINAL = "contenido FIT de prueba".getBytes(UTF_8);

    /** IP única por prueba: el limitador de intentos es un singleton en memoria y una IP bloqueada daría 429. */
    private static final AtomicInteger NEXT_IP = new AtomicInteger();

    private String clientIp;

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
    AccountExport export;

    private TestSessions.Session session;
    private UUID activityId;
    private UUID bikeId;

    @BeforeEach
    void ownerWithData() throws Exception {
        clientIp = "10.9." + (NEXT_IP.get() / 250) + "." + (NEXT_IP.getAndIncrement() % 250 + 1);
        session = TestSessions.freshOwner(mvc, jdbc, bootstrap);
        bikeId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO bikes (id, user_id, name, bike_type, created_at, updated_at)
                        VALUES (:id, :user, 'Ruta', 'ROAD', now(), now())""").param("id", bikeId).param("user", session.userId()).update();
        jdbc.sql("""
                        INSERT INTO devices (id, user_id, display_name, created_at)
                        VALUES (:id, :user, 'Edge', now())""")
                .param("id", UUID.randomUUID())
                .param("user", session.userId())
                .update();
        activityId = activity();
    }

    /** Actividad sintética con track de 3 puntos, una altitud faltante y una vuelta. */
    private UUID activity() throws Exception {
        var streams = new EnumMap<StreamType, Double[]>(StreamType.class);
        streams.put(StreamType.TIME, new Double[] {0.0, 1_000.0, 2_000.0});
        streams.put(StreamType.LATITUDE, new Double[] {19.0, 19.0001, 19.0002});
        streams.put(StreamType.LONGITUDE, new Double[] {-99.0, -99.0, -99.0});
        streams.put(StreamType.ALTITUDE, new Double[] {1_500.0, null, 1_501.5});
        var start = Instant.parse("2026-10-04T13:00:00Z");
        var stored = store.store(session.userId(), new ByteArrayInputStream(ORIGINAL));
        return registration.register(new NewActivity(
                session.userId(),
                bikeId,
                null,
                null,
                ActivitySubType.ROAD,
                start,
                ZoneId.of("America/Mexico_City"),
                "FIT",
                null,
                List.of(
                        new MetricValue(Metric.ELAPSED_TIME, Source.DERIVED, 2, "derived-metrics/1", null),
                        new MetricValue(Metric.MOVING_TIME, Source.DEVICE_REPORTED, 2, null, null),
                        new MetricValue(Metric.DISTANCE, Source.DERIVED, 22.2, "derived-metrics/1", null)),
                new NewActivity.Samples(3, new int[] {0, 0, 0}, streams),
                List.of(new NewActivity.Lap(1, start, 2.0, 2.0, 22.2, null, null, 120, 130, null, null, null)),
                new NewActivity.OriginalFile(
                        stored.storageKey(), stored.sha256(), stored.sizeBytes(), "velora-fit", "1")));
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        var entries = new HashMap<String, byte[]>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private MockHttpServletRequestBuilder deletion(String password) {
        return session.authorize(post("/api/v1/me/deletion"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + password + "\"}")
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                });
    }

    private Path userOriginals() {
        return ORIGINALS.resolve(session.userId().toString());
    }

    @Test
    void exportContainsProfileCatalogActivitiesAndOriginals() throws Exception {
        var response = mvc.perform(session.authorize(get("/api/v1/me/export")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string(
                                "Content-Disposition",
                                org.hamcrest.Matchers.matchesPattern(
                                        "attachment; filename=\"velora-export-\\d{4}-\\d{2}-\\d{2}\\.zip\"")))
                .andReturn()
                .getResponse();

        var files = unzip(response.getContentAsByteArray());
        var folder = "activities/" + activityId + "/";
        assertThat(files)
                .containsOnlyKeys(
                        "manifest.json",
                        "profile.json",
                        "bikes.json",
                        "devices.json",
                        folder + "activity.json",
                        folder + "track.geojson",
                        folder + "streams.csv",
                        folder + "original.fit");

        var manifest = new String(files.get("manifest.json"), UTF_8);
        assertThat(JsonPath.<String>read(manifest, "$.format")).isEqualTo("velora-export/1");
        assertThat(JsonPath.<Integer>read(manifest, "$.counts.activities")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(manifest, "$.counts.bikes")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(manifest, "$.counts.devices")).isEqualTo(1);

        var profile = new String(files.get("profile.json"), UTF_8);
        assertThat(JsonPath.<String>read(profile, "$.email")).isEqualTo(session.email());
        assertThat(profile).doesNotContainIgnoringCase("password").doesNotContain("argon2");

        var activity = new String(files.get(folder + "activity.json"), UTF_8);
        assertThat(JsonPath.<String>read(activity, "$.summary.id")).isEqualTo(activityId.toString());
        assertThat(JsonPath.<List<?>>read(activity, "$.laps")).hasSize(1);
        assertThat(JsonPath.<Object>read(activity, "$.metricsProvenance")).isInstanceOf(Map.class);

        var track = new String(files.get(folder + "track.geojson"), UTF_8);
        assertThat(JsonPath.<String>read(track, "$.type")).isEqualTo("Feature");
        assertThat(JsonPath.<List<?>>read(track, "$.geometry.coordinates[0]")).hasSize(3);

        assertThat(new String(files.get(folder + "streams.csv"), UTF_8)).isEqualTo("""
                        time_ms,latitude,longitude,altitude_m
                        0,19,-99,1500
                        1000,19.0001,-99,
                        2000,19.0002,-99,1501.5
                        """);
        assertThat(files.get(folder + "original.fit")).isEqualTo(ORIGINAL);
    }

    @Test
    void onlyOneExportAtATimePerUser() throws Exception {
        export.acquire(session.userId());
        try {
            mvc.perform(session.authorize(get("/api/v1/me/export")))
                    .andExpect(status().isConflict())
                    .andExpect(openApi().isValid(CONTRACT))
                    .andExpect(jsonPath("$.title").value("Exportación en curso"));
        } finally {
            export.release(session.userId());
        }
        mvc.perform(session.authorize(get("/api/v1/me/export"))).andExpect(status().isOk());
    }

    @Test
    void exportRequiresASession() throws Exception {
        mvc.perform(get("/api/v1/me/export")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordDeletesNothing() throws Exception {
        mvc.perform(deletion("no-es-la-contraseña"))
                .andExpect(status().isForbidden())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.title").value("Contraseña incorrecta"));

        assertThat(rowsOwnedBy(session.userId())).isNotEmpty();
        assertThat(userOriginals()).isDirectory();
        mvc.perform(session.authorize(get("/api/v1/me"))).andExpect(status().isOk());
    }

    @Test
    void repeatedWrongPasswordsAreThrottled() throws Exception {
        var status = 0;
        for (int i = 0; i < 20 && status != 429; i++) {
            status = mvc.perform(deletion("incorrecta-" + i))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        }
        assertThat(status).isEqualTo(429);
        // Aun con la contraseña correcta, mientras dure la espera.
        mvc.perform(deletion(TestSessions.PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(openApi().isValid(CONTRACT));
        assertThat(rowsOwnedBy(session.userId())).isNotEmpty();
    }

    @Test
    void deletionRemovesEveryRowAndFileOfTheUser() throws Exception {
        assertThat(userOriginals()).isDirectory();
        // Un huérfano de un borrado anterior también debe desaparecer.
        Files.writeString(userOriginals().resolve(UUID.randomUUID().toString()), "huérfano");

        mvc.perform(deletion(TestSessions.PASSWORD)).andExpect(status().isNoContent());

        assertThat(rowsOwnedBy(session.userId())).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE id = :id")
                        .param("id", session.userId())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(userOriginals()).doesNotExist();
        mvc.perform(session.authorize(get("/api/v1/me"))).andExpect(status().isUnauthorized());
    }

    @Test
    void deletionRequiresAPassword() throws Exception {
        mvc.perform(session.authorize(post("/api/v1/me/deletion"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
        assertThat(rowsOwnedBy(session.userId())).isNotEmpty();
    }

    /**
     * Tablas con filas del usuario, buscadas en todas las tablas con columna {@code user_id}: una tabla nueva queda
     * cubierta sin tocar esta prueba. Las tablas sin {@code user_id} dependen de una que sí lo tiene (cascada).
     */
    private List<String> rowsOwnedBy(UUID userId) {
        var tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.columns
                        WHERE table_schema = 'public' AND column_name = 'user_id'
                        ORDER BY table_name""").query(String.class).list();
        assertThat(tables).contains("activities", "activity_files", "bikes", "devices", "import_jobs");
        return tables.stream()
                .filter(table -> jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = :id")
                                .param("id", userId)
                                .query(Long.class)
                                .single()
                        > 0)
                .toList();
    }
}
