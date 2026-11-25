package mx.velora.api.importing;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Grabaciones secundarias (ADR-032): la grabación del reloj se guarda junto a la actividad de la salida y nunca se
 * vuelve una segunda actividad, llegue antes o después que ella.
 */
@IntegrationTest
class SecondaryRecordingIntegrationTest {

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();
    private static final Instant T0 = Instant.parse("2026-11-22T14:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    @Autowired
    ImportWorker worker;

    private TestSessions.Session session;

    @BeforeEach
    void login() throws Exception {
        session = TestSessions.freshOwner(mvc, jdbc, bootstrap);
    }

    /** GPX sintético: un punto cada 10 s durante {@code minutes}, con FC; {@code lon0} cambia el recorrido. */
    private static byte[] gpx(Instant start, int minutes, double lon0) {
        var points = new StringBuilder();
        for (int i = 0; i <= minutes * 6; i++) {
            var time = start.plusSeconds(i * 10L);
            points.append(String.format(
                    Locale.ROOT,
                    "<trkpt lat=\"%.6f\" lon=\"%.6f\"><ele>2240.0</ele><time>%s</time>"
                            + "<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>%d</gpxtpx:hr>"
                            + "</gpxtpx:TrackPointExtension></extensions></trkpt>",
                    19.40 + i * 0.0004,
                    lon0,
                    time,
                    120 + (i % 20)));
        }
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx version="1.1" creator="pruebas" xmlns="http://www.topografix.com/GPX/1/1"
                     xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
                <trk><type>cycling</type><trkseg>%s</trkseg></trk></gpx>
                """.formatted(points)).getBytes(StandardCharsets.UTF_8);
    }

    private ResultActions uploadRecording(byte[] content, UUID clientId) throws Exception {
        var request = multipart("/api/v1/secondary-recordings")
                .file(new MockMultipartFile("file", "track.gpx", MediaType.APPLICATION_OCTET_STREAM_VALUE, content))
                .param("source", "WATCH")
                .param("clientRecordingId", clientId.toString())
                .param("completeness", "COMPLETE");
        return mvc.perform(request.header("Authorization", "Bearer " + session.accessToken()));
    }

    /** Sube la grabación, procesa la cola y devuelve el id de la grabación creada. */
    private String recording(byte[] content, UUID clientId) throws Exception {
        var accepted = uploadRecording(content, clientId)
                .andExpect(status().isAccepted())
                .andExpect(OpenApiContract.multipartIsValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
        worker.drain();
        var job = job(JsonPath.read(accepted, "$.jobId"));
        assertThat(JsonPath.<String>read(job, "$.kind")).isEqualTo("SECONDARY");
        assertThat(JsonPath.<String>read(job, "$.status")).isEqualTo("COMPLETED");
        assertThat(JsonPath.<Object>read(job, "$.activityId")).isNull();
        return JsonPath.read(job, "$.secondaryRecordingId");
    }

    /** Importa una actividad normal y devuelve su id. */
    private String activity(byte[] content) throws Exception {
        var accepted = mvc.perform(multipart("/api/v1/activities/imports")
                        .file(new MockMultipartFile(
                                "file", "rodada.gpx", MediaType.APPLICATION_OCTET_STREAM_VALUE, content))
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        worker.drain();
        var job = job(JsonPath.read(accepted, "$.jobId"));
        assertThat(JsonPath.<String>read(job, "$.status")).isEqualTo("COMPLETED");
        return JsonPath.read(job, "$.activityId");
    }

    private String job(String jobId) throws Exception {
        return mvc.perform(session.authorize(get("/api/v1/imports/" + jobId)))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private ResultActions getRecording(String id) throws Exception {
        return mvc.perform(session.authorize(get("/api/v1/secondary-recordings/" + id)))
                .andExpect(openApi().isValid(CONTRACT));
    }

    private long activityCount() {
        return jdbc.sql("SELECT count(*) FROM activities WHERE user_id = :user")
                .param("user", session.userId())
                .query(Long.class)
                .single();
    }

    @Test
    void laGrabacionQueLlegaAntesSeAdjuntaCuandoLlegaLaActividad() throws Exception {
        var recordingId = recording(gpx(T0, 60, -99.10), UUID.randomUUID());

        getRecording(recordingId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNASSIGNED"))
                .andExpect(jsonPath("$.source").value("WATCH"))
                .andExpect(jsonPath("$.pointCount").value(361))
                .andExpect(jsonPath("$.averageHeartRateBpm").isNumber());
        // No es una actividad: la lista y la analítica no la ven.
        assertThat(activityCount()).isZero();
        mvc.perform(session.authorize(get("/api/v1/activities")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        // El ciclocomputador llega después (p. ej. por Strava), con un minuto de desfase y otro recorrido.
        var activityId = activity(gpx(T0.plus(1, ChronoUnit.MINUTES), 60, -99.20));

        getRecording(recordingId)
                .andExpect(jsonPath("$.status").value("ATTACHED"))
                .andExpect(jsonPath("$.activityId").value(activityId))
                .andExpect(jsonPath("$.attachedBy").value("AUTO"));
        mvc.perform(session.authorize(get("/api/v1/activities/" + activityId + "/secondary-recordings")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(recordingId));
        assertThat(activityCount()).isEqualTo(1);
    }

    @Test
    void laGrabacionQueLlegaDespuesSeAdjuntaAlProcesarse() throws Exception {
        var activityId = activity(gpx(T0, 90, -99.20));
        var recordingId = recording(gpx(T0.plus(5, ChronoUnit.MINUTES), 80, -99.10), UUID.randomUUID());

        getRecording(recordingId).andExpect(jsonPath("$.activityId").value(activityId));
        assertThat(activityCount()).isEqualTo(1);
    }

    @Test
    void sinSolapeSuficienteOConVariasCandidatasQuedaSinAsignar() throws Exception {
        // Solape de 20 min sobre 60: menos del 50 %.
        activity(gpx(T0, 60, -99.20));
        var lejana = recording(gpx(T0.plus(40, ChronoUnit.MINUTES), 60, -99.10), UUID.randomUUID());
        getRecording(lejana).andExpect(jsonPath("$.status").value("UNASSIGNED"));

        // Dos actividades que encajan igual: no se elige al azar.
        var t1 = T0.plus(1, ChronoUnit.DAYS);
        activity(gpx(t1, 60, -99.30));
        activity(gpx(t1, 60, -99.40));
        var ambigua = recording(gpx(t1, 60, -99.10), UUID.randomUUID());
        getRecording(ambigua).andExpect(jsonPath("$.status").value("UNASSIGNED"));

        mvc.perform(session.authorize(get("/api/v1/secondary-recordings").param("status", "UNASSIGNED")))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void laSubidaEsIdempotentePorElIdDelDispositivo() throws Exception {
        var clientId = UUID.randomUUID();
        var file = gpx(T0, 30, -99.10);

        // Pendiente: el mismo archivo devuelve el mismo trabajo.
        var first =
                uploadRecording(file, clientId).andExpect(status().isAccepted()).andReturn();
        var again =
                uploadRecording(file, clientId).andExpect(status().isAccepted()).andReturn();
        assertThat(JsonPath.<String>read(again.getResponse().getContentAsString(), "$.jobId"))
                .isEqualTo(JsonPath.<String>read(first.getResponse().getContentAsString(), "$.jobId"));

        worker.drain();
        // Procesada: el mismo archivo devuelve la grabación (200) sin duplicarla.
        uploadRecording(file, clientId)
                .andExpect(status().isOk())
                .andExpect(OpenApiContract.multipartIsValid(CONTRACT))
                .andExpect(jsonPath("$.clientRecordingId").value(clientId.toString()));
        // Otro archivo con el mismo id: conflicto.
        uploadRecording(gpx(T0, 31, -99.10), clientId)
                .andExpect(status().isConflict())
                .andExpect(OpenApiContract.multipartIsValid(CONTRACT))
                .andExpect(jsonPath("$.code").value("RECORDING_CONFLICT"));

        assertThat(jdbc.sql("SELECT count(*) FROM secondary_recordings")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void unArchivoYaImportadoComoActividadSeRechaza() throws Exception {
        var file = gpx(T0, 30, -99.10);
        activity(file);

        uploadRecording(file, UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_IMPORTED_AS_ACTIVITY"));
    }

    @Test
    void borrarLaActividadDejaLaGrabacionSinAsignarNoLaBorra() throws Exception {
        var activityId = activity(gpx(T0, 60, -99.20));
        var recordingId = recording(gpx(T0, 60, -99.10), UUID.randomUUID());

        mvc.perform(session.authorize(delete("/api/v1/activities/" + activityId)))
                .andExpect(status().isNoContent());

        getRecording(recordingId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNASSIGNED"))
                .andExpect(jsonPath("$.attachedBy").isEmpty());
    }

    @Test
    void laDecisionDelUsuarioNoLaMueveLaAutomatica() throws Exception {
        var activityId = activity(gpx(T0, 60, -99.20));
        var recordingId = recording(gpx(T0, 60, -99.10), UUID.randomUUID());

        mvc.perform(session.authorize(put("/api/v1/secondary-recordings/" + recordingId + "/activity"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityId\":null}"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.status").value("UNASSIGNED"));

        // Una actividad nueva que encaja no se la lleva: el usuario la desasignó.
        mvc.perform(session.authorize(delete("/api/v1/activities/" + activityId)));
        activity(gpx(T0, 60, -99.30));
        getRecording(recordingId).andExpect(jsonPath("$.status").value("UNASSIGNED"));

        // Adjuntar a mano a una actividad ajena o inexistente: 404.
        mvc.perform(session.authorize(put("/api/v1/secondary-recordings/" + recordingId + "/activity"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void recuperarComoActividadDejaUnaSolaCopia() throws Exception {
        var recordingId = recording(gpx(T0, 45, -99.10), UUID.randomUUID());
        var storageKey = jdbc.sql("SELECT storage_key FROM secondary_recordings WHERE id = CAST(:id AS uuid)")
                .param("id", recordingId)
                .query(String.class)
                .single();

        var accepted = mvc.perform(session.authorize(post("/api/v1/secondary-recordings/" + recordingId + "/promote")))
                .andExpect(status().isAccepted())
                .andExpect(openApi().isValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
        // Mientras se recupera no se puede borrar.
        mvc.perform(session.authorize(delete("/api/v1/secondary-recordings/" + recordingId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTION_PENDING"));
        worker.drain();

        var job = job(JsonPath.read(accepted, "$.jobId"));
        assertThat(JsonPath.<String>read(job, "$.kind")).isEqualTo("PROMOTE");
        assertThat(JsonPath.<String>read(job, "$.status")).isEqualTo("COMPLETED");
        assertThat(activityCount()).isEqualTo(1);
        getRecording(recordingId).andExpect(status().isNotFound());
        // El original pasó a la actividad.
        assertThat(jdbc.sql("SELECT count(*) FROM activity_files WHERE storage_key = :key")
                        .param("key", storageKey)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void unaGrabacionAdjuntaNoSeRecupera() throws Exception {
        activity(gpx(T0, 60, -99.20));
        var recordingId = recording(gpx(T0, 60, -99.10), UUID.randomUUID());

        mvc.perform(session.authorize(post("/api/v1/secondary-recordings/" + recordingId + "/promote")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_ATTACHED"));
    }

    @Test
    void borrarLaGrabacionBorraSuOriginal() throws Exception {
        var recordingId = recording(gpx(T0, 30, -99.10), UUID.randomUUID());

        mvc.perform(session.authorize(delete("/api/v1/secondary-recordings/" + recordingId)))
                .andExpect(status().isNoContent())
                .andExpect(openApi().isValid(CONTRACT));

        getRecording(recordingId).andExpect(status().isNotFound());
    }

    @Test
    void unArchivoInvalidoTerminaRechazadoSinCrearGrabacion() throws Exception {
        var accepted = uploadRecording("no es un gpx".getBytes(StandardCharsets.UTF_8), UUID.randomUUID())
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        worker.drain();

        var job = job(JsonPath.read(accepted, "$.jobId"));
        assertThat(JsonPath.<String>read(job, "$.status")).isEqualTo("REJECTED");
        assertThat(jdbc.sql("SELECT count(*) FROM secondary_recordings")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void sinSesionNoHayAcceso() throws Exception {
        mvc.perform(get("/api/v1/secondary-recordings")).andExpect(status().isUnauthorized());
    }
}
