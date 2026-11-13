package mx.velora.api.benchmark;

import static org.assertj.core.api.Assumptions.assumeThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import mx.velora.api.IntegrationTest;
import mx.velora.api.TestSessions;
import mx.velora.api.identity.user.OwnerBootstrap;
import mx.velora.api.importing.job.ImportWorker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Rendimiento con las rodadas reales del usuario, <b>solo en local</b> (Fase 7; ADR-012 pedía repetir el benchmark con
 * datos propios). Importa cada archivo por la API completa contra el PostGIS de Testcontainers y mide la importación, el
 * espacio y las lecturas de cada pantalla. El reporte solo tiene tiempos, conteos y tamaños: ni coordenadas, ni títulos,
 * ni coordenadas de teselas.
 *
 * <p>{@code ./gradlew analyzeLocalFiles -PlocalFiles=/ruta --tests '*LocalPerformanceReport'}. Resultado en
 * {@code build/local-data/rendimiento.md} (fuera de Git).
 */
@IntegrationTest
@Tag("local-data")
class LocalPerformanceReport {

    /** Repeticiones de cada lectura tras calentar; se reporta la mediana y el máximo. */
    private static final int RUNS = 15;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    @Autowired
    ImportWorker worker;

    private TestSessions.Session session;

    @Test
    void report() throws Exception {
        var dir = System.getProperty("velora.localFiles");
        assumeThat(dir).as("define -PlocalFiles=<carpeta>").isNotBlank();
        session = TestSessions.freshOwner(mvc, jdbc, bootstrap);
        var files =
                Files.list(Path.of(dir)).filter(Files::isRegularFile).sorted().toList();

        var out = new StringBuilder("# Rendimiento con rodadas reales\n\n");
        out.append(
                "| Archivo | Formato | Tamaño | Resultado | Importar (ms) | Muestras | Streams en BD | Recorrido (puntos) |\n");
        out.append("|---|---|---|---|---|---|---|---|\n");
        var imported = new ArrayList<String>();
        long totalImportMs = 0;
        for (var file : files) {
            var content = Files.readAllBytes(file);
            var start = System.nanoTime();
            var upload = multipart("/api/v1/activities/imports")
                    .file(new MockMultipartFile(
                            "file", file.getFileName().toString(), MediaType.APPLICATION_OCTET_STREAM_VALUE, content));
            var accepted = mvc.perform(upload.header("Authorization", "Bearer " + session.accessToken()))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            worker.drain();
            var elapsed = (System.nanoTime() - start) / 1_000_000;
            var job = body(get("/api/v1/imports/" + JsonPath.read(accepted, "$.jobId")));
            String status = JsonPath.read(job, "$.status");
            String activityId = JsonPath.read(job, "$.activityId");
            var name = file.getFileName().toString();
            if (!"COMPLETED".equals(status) || activityId == null) {
                out.append("| %s | — | %s | %s | %d | — | — | — |%n"
                        .formatted(name, size(content.length), status, elapsed));
                continue;
            }
            totalImportMs += elapsed;
            imported.add(activityId);
            var stats = jdbc.sql("""
                            SELECT a.source_type,
                                   (SELECT coalesce(sum(octet_length(s.payload)), 0) FROM activity_streams s
                                    WHERE s.activity_id = a.id) AS stream_bytes,
                                   (SELECT coalesce(max(s.sample_count), 0) FROM activity_streams s
                                    WHERE s.activity_id = a.id) AS samples,
                                   coalesce(t.point_count, 0) AS points
                            FROM activities a LEFT JOIN activity_tracks t ON t.activity_id = a.id
                            WHERE a.id = CAST(:id AS uuid)
                            """).param("id", activityId).query().singleRow();
            out.append("| %s | %s | %s | %s | %d | %s | %s | %s |%n"
                    .formatted(
                            name,
                            stats.get("source_type"),
                            size(content.length),
                            status,
                            elapsed,
                            stats.get("samples"),
                            size(((Number) stats.get("stream_bytes")).longValue()),
                            stats.get("points")));
        }
        out.append("%nImportadas: %d de %d archivos, %d ms en total.%n"
                .formatted(imported.size(), files.size(), totalImportMs));
        assumeThat(imported).as("ningún archivo se importó").isNotEmpty();

        // Las celdas de exploración se calculan por eventos después de importar; se espera a que terminen.
        Thread.sleep(2_000);

        var reads = new LinkedHashMap<String, long[]>();
        var first = imported.getFirst();
        reads.put("Detalle de una rodada", time(get("/api/v1/activities/" + first)));
        reads.put(
                "Gráficas (streams sin posiciones)",
                time(get("/api/v1/activities/" + first + "/streams")
                        .param("types", "TIME,DISTANCE,ALTITUDE,SPEED,HEART_RATE,POWER,CADENCE,TEMPERATURE")));
        reads.put(
                "Recorrido para el mapa (zoom 14)",
                time(get("/api/v1/activities/" + first + "/track").param("zoom", "14")));
        reads.put("Subidas", time(get("/api/v1/activities/" + first + "/climbs")));
        reads.put("Caminos nuevos", time(get("/api/v1/geo/new-territory").param("activityId", first)));
        reads.put("Historial (primera página)", time(get("/api/v1/activities").param("limit", "50")));
        reads.put("Inicio (mes)", time(get("/api/v1/dashboard/summary").param("period", "month")));
        reads.put("Inicio (todo)", time(get("/api/v1/dashboard/summary").param("period", "all")));
        reads.put(
                "Rendimiento: tendencias por semana",
                time(get("/api/v1/analytics/timeseries").param("granularity", "week")));
        reads.put("Rendimiento: récords", time(get("/api/v1/analytics/records")));
        reads.put("Rendimiento: Eddington", time(get("/api/v1/analytics/eddington")));
        reads.put("Rendimiento: zonas cardiacas", time(get("/api/v1/analytics/heart-rate")));
        reads.put("Rendimiento: carga de entrenamiento", time(get("/api/v1/analytics/training-load")));
        reads.put("Explorador: resumen de celdas", time(get("/api/v1/geo/exploration/summary")));

        out.append(
                "\n## Lecturas (%d repeticiones tras calentar)\n\n| Pantalla | Mediana (ms) | Máximo (ms) |\n|---|---|---|\n"
                        .formatted(RUNS));
        for (Map.Entry<String, long[]> entry : reads.entrySet()) {
            var sorted = entry.getValue().clone();
            Arrays.sort(sorted);
            out.append("| %s | %.1f | %.1f |%n"
                    .formatted(entry.getKey(), sorted[sorted.length / 2] / 1e6, sorted[sorted.length - 1] / 1e6));
        }
        out.append("\nEntorno: PostgreSQL + PostGIS (Testcontainers), MockMvc (sin red ni TLS), Java %s, %s.%n"
                .formatted(System.getProperty("java.version"), System.getProperty("os.arch")));

        System.out.println(out);
        var target = Path.of("build", "local-data", "rendimiento.md");
        Files.createDirectories(target.getParent());
        Files.writeString(target, out);
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(session.authorize(request)).andReturn().getResponse().getContentAsString();
    }

    /** Nanosegundos de cada repetición; las primeras tres solo calientan. */
    private long[] time(MockHttpServletRequestBuilder request) throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(session.authorize(request)).andReturn();
        }
        var times = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            var start = System.nanoTime();
            var status = mvc.perform(session.authorize(request))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            times[i] = System.nanoTime() - start;
            if (status >= 400) {
                throw new IllegalStateException("La lectura respondió " + status);
            }
        }
        return times;
    }

    private static String size(long bytes) {
        return bytes < 1024 * 1024
                ? String.format(Locale.ROOT, "%.0f KiB", bytes / 1024.0)
                : String.format(Locale.ROOT, "%.1f MiB", bytes / 1024.0 / 1024.0);
    }
}
