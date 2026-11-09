package mx.velora.api.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import mx.velora.api.IntegrationTest;
import mx.velora.api.TestSessions;
import mx.velora.api.identity.user.OwnerBootstrap;
import mx.velora.api.importing.job.ImportWorker;
import mx.velora.api.importing.parsing.FitFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Criterio de salida de la Fase 2 ({@code 08-roadmap.md}): importar y consultar 100 actividades de prueba, de punta a
 * punta por la API. Archivos FIT sintéticos de 30 min a 1 Hz con GPS, altitud, distancia, FC, cadencia y potencia.
 */
@IntegrationTest
class HundredActivitiesExitCriterionTest {

    private static final int ACTIVITIES = 100;
    private static final int SAMPLES = 1_800;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    @Autowired
    ImportWorker worker;

    /**
     * Rodada sintética distinta por índice: empieza un día antes que la anterior (fechas futuras se rechazan) y en
     * otra zona, con file_id distinto.
     */
    private static byte[] ride(int index) {
        var builder = new RideBuilder(FitFixtures.START.minusSeconds(index * 86_400L));
        for (int t = 0; t < SAMPLES; t++) {
            builder.record(t, index);
        }
        return builder.build();
    }

    @Test
    void importsAndQueriesOneHundredActivities() throws Exception {
        var session = TestSessions.freshOwner(mvc, jdbc, bootstrap);
        var started = Instant.now();
        for (int i = 0; i < ACTIVITIES; i++) {
            mvc.perform(multipart("/api/v1/activities/imports")
                            .file(new MockMultipartFile("file", "rodada-" + i + ".fit", null, ride(i)))
                            .header("Authorization", "Bearer " + session.accessToken()))
                    .andExpect(status().isAccepted());
        }
        var uploaded = Instant.now();

        assertThat(worker.drain()).isEqualTo(ACTIVITIES);
        var processed = Instant.now();

        assertThat(jdbc.sql("SELECT status, count(*) FROM import_jobs GROUP BY status")
                        .query((rs, n) -> rs.getString(1) + "=" + rs.getInt(2))
                        .list())
                .containsExactly("COMPLETED=" + ACTIVITIES);

        var seen = new HashSet<String>();
        var pages = new ArrayList<Integer>();
        String cursor = null;
        do {
            var json = mvc.perform(session.authorize(
                            get("/api/v1/activities?limit=25" + (cursor == null ? "" : "&cursor=" + cursor))))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            List<String> ids = JsonPath.read(json, "$.items[*].id");
            pages.add(ids.size());
            seen.addAll(ids);
            cursor = JsonPath.read(json, "$.nextCursor");
        } while (cursor != null);
        assertThat(pages).containsExactly(25, 25, 25, 25);
        assertThat(seen).hasSize(ACTIVITIES);

        for (var id : seen.stream().limit(10).toList()) {
            mvc.perform(session.authorize(get("/api/v1/activities/" + id))).andExpect(status().isOk());
            var streams = mvc.perform(session.authorize(get("/api/v1/activities/" + id + "/streams?types=POWER")))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat((List<?>) JsonPath.read(streams, "$.streams.POWER")).hasSize(SAMPLES);
        }
        var queried = Instant.now();

        System.out.printf(
                "Criterio de salida: %d actividades de %d muestras — subida %d ms, procesamiento %d ms, consulta %d ms%n",
                ACTIVITIES,
                SAMPLES,
                java.time.Duration.between(started, uploaded).toMillis(),
                java.time.Duration.between(uploaded, processed).toMillis(),
                java.time.Duration.between(processed, queried).toMillis());
    }

    /** FIT con GPS, altitud, distancia, FC, cadencia y potencia a 1 Hz desde {@code start}. */
    private static final class RideBuilder {
        private final java.util.List<com.garmin.fit.Mesg> mesgs = new java.util.ArrayList<>();
        private final Instant start;

        RideBuilder(Instant start) {
            this.start = start;
            var fileId = new com.garmin.fit.FileIdMesg();
            fileId.setType(com.garmin.fit.File.ACTIVITY);
            fileId.setManufacturer(FitFixtures.MANUFACTURER_DEVELOPMENT);
            fileId.setProduct(1);
            fileId.setSerialNumber(FitFixtures.SERIAL_NUMBER);
            fileId.setTimeCreated(new com.garmin.fit.DateTime(start));
            mesgs.add(fileId);
        }

        void record(int second, int index) {
            var r = new com.garmin.fit.RecordMesg();
            r.setTimestamp(new com.garmin.fit.DateTime(start.plusSeconds(second)));
            r.setPositionLat(FitFixtures.semicircles(19.0 + index * 0.01 + second * 0.00005));
            r.setPositionLong(FitFixtures.semicircles(-99.0 + index * 0.01));
            r.setEnhancedAltitude((float) (1_500 + 50 * Math.sin(second / 120.0)));
            r.setDistance(second * 5.5f);
            r.setHeartRate((short) (120 + second % 30));
            r.setCadence((short) 85);
            r.setPower(180 + second % 40);
            mesgs.add(r);
        }

        byte[] build() {
            var encoder = new com.garmin.fit.BufferEncoder(com.garmin.fit.Fit.ProtocolVersion.V2_0);
            encoder.write(mesgs);
            return encoder.close();
        }
    }
}
