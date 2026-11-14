package mx.velora.api.activity;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import mx.velora.api.activity.persistence.ActivityQueries;
import mx.velora.api.platform.storage.OriginalFileStore;
import mx.velora.api.platform.storage.OriginalFileStore.StoredFile;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Escribe las actividades del usuario en la exportación (ADR-028), una carpeta por actividad:
 *
 * <ul>
 *   <li>{@code activity.json}: resumen, métricas con su procedencia y vueltas;
 *   <li>{@code track.geojson}: recorrido completo, sin simplificar ni recortar por privacidad (es para el dueño);
 *   <li>{@code streams.csv}: una fila por muestra;
 *   <li>{@code original.<fit|gpx|tcx>}: el archivo importado, verificado contra su SHA-256.
 * </ul>
 */
@Service
public class ActivityExport {

    private record OriginalRow(String format, String storageKey, byte[] sha256, long sizeBytes) {}

    private final JdbcClient jdbc;
    private final ActivityQueries queries;
    private final OriginalFileStore store;
    private final JsonMapper json;

    ActivityExport(JdbcClient jdbc, ActivityQueries queries, OriginalFileStore store, JsonMapper json) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.store = store;
        this.json = json;
    }

    /** @return número de actividades escritas */
    public int writeTo(UUID userId, ZipOutputStream zip, String folder) throws IOException {
        var ids = jdbc.sql("SELECT id FROM activities WHERE user_id = :user ORDER BY started_at, id")
                .param("user", userId)
                .query(UUID.class)
                .list();
        var written = 0;
        for (var id : ids) {
            var detail = queries.detail(userId, id);
            if (detail.isEmpty()) {
                continue;
            }
            var prefix = folder + "/" + id + "/";
            writeActivity(zip, prefix, detail.get(), queries.laps(id));
            writeTrack(zip, prefix, id);
            writeStreams(zip, prefix, queries.streams(id, null));
            writeOriginals(zip, prefix, userId, id);
            written++;
        }
        return written;
    }

    private void writeActivity(ZipOutputStream zip, String prefix, ActivityQueries.Detail detail, List<?> laps)
            throws IOException {
        var node = json.valueToTree(detail).asObject();
        node.remove("metricsProvenanceJson");
        node.set("metricsProvenance", json.readTree(detail.metricsProvenanceJson()));
        node.set("laps", json.valueToTree(laps));
        put(zip, prefix + "activity.json", json.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
    }

    private void writeTrack(ZipOutputStream zip, String prefix, UUID id) throws IOException {
        var geometry = queries.fullTrackGeoJson(id);
        if (geometry.isEmpty()) {
            return;
        }
        var feature = json.createObjectNode();
        feature.put("type", "Feature");
        feature.set("geometry", json.readTree(geometry.get()));
        feature.putObject("properties").put("activityId", id.toString());
        put(zip, prefix + "track.geojson", json.writeValueAsBytes(feature));
    }

    private static void writeStreams(ZipOutputStream zip, String prefix, ActivityQueries.Streams streams)
            throws IOException {
        if (streams.values().isEmpty()) {
            return;
        }
        var types = List.copyOf(streams.values().keySet());
        zip.putNextEntry(new ZipEntry(prefix + "streams.csv"));
        // Sin cerrar el writer: cerraría el ZIP completo.
        var out = new OutputStreamWriter(zip, UTF_8);
        out.write(String.join(",", types.stream().map(ActivityExport::column).toList()));
        out.write('\n');
        for (int i = 0; i < streams.sampleCount(); i++) {
            for (int c = 0; c < types.size(); c++) {
                if (c > 0) {
                    out.write(',');
                }
                var values = streams.values().get(types.get(c));
                out.write(number(values != null && i < values.length ? values[i] : null));
            }
            out.write('\n');
        }
        out.flush();
        zip.closeEntry();
    }

    private void writeOriginals(ZipOutputStream zip, String prefix, UUID userId, UUID activityId) throws IOException {
        var files = jdbc.sql("""
                        SELECT format, storage_key, sha256, file_size_bytes FROM activity_files
                        WHERE activity_id = :id AND user_id = :user ORDER BY imported_at, id""")
                .param("id", activityId)
                .param("user", userId)
                .query((rs, row) -> new OriginalRow(
                        rs.getString("format"),
                        rs.getString("storage_key"),
                        rs.getBytes("sha256"),
                        rs.getLong("file_size_bytes")))
                .list();
        for (int i = 0; i < files.size(); i++) {
            var file = files.get(i);
            var name = (i == 0 ? "original" : "original-" + (i + 1)) + "."
                    + file.format().toLowerCase(Locale.ROOT);
            zip.putNextEntry(new ZipEntry(prefix + name));
            try (var in = store.open(new StoredFile(file.storageKey(), file.sha256(), file.sizeBytes()))) {
                in.transferTo(zip);
            }
            zip.closeEntry();
        }
    }

    private static void put(ZipOutputStream zip, String path, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content);
        zip.closeEntry();
    }

    /** Encabezado del CSV con la unidad de cada stream. */
    static String column(StreamType type) {
        return switch (type) {
            case TIME -> "time_ms";
            case LATITUDE -> "latitude";
            case LONGITUDE -> "longitude";
            case ALTITUDE -> "altitude_m";
            case DISTANCE -> "distance_m";
            case SPEED -> "speed_mps";
            case HEART_RATE -> "heart_rate_bpm";
            case CADENCE -> "cadence_rpm";
            case POWER -> "power_w";
            case TEMPERATURE -> "temperature_c";
        };
    }

    /** Sin notación científica; vacío si falta la muestra. */
    static String number(@Nullable Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return "";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
