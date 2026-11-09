package mx.velora.api.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import javax.sql.DataSource;
import mx.velora.api.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Compara formas de almacenar streams de actividad (02-domain-and-data-model.md: "decidir después de un benchmark").
 *
 * <p>Datos sintéticos y deterministas: {@value #ACTIVITIES} actividades de 3 h a 1 Hz con 10 streams. Mide espacio,
 * tiempo de escritura y tiempo de lectura de una actividad completa y de un solo stream. Ejecutar con
 * {@code ./gradlew benchmark}; el resultado queda en {@code build/benchmark/streams.md}.
 */
@IntegrationTest
@Tag("benchmark")
class StreamStorageBenchmark {

    static final int ACTIVITIES = 100;
    static final int SAMPLES = 3 * 3600;
    static final int READS = 50;

    /** Escala de cuantización por stream para el formato binario (resolución igual o mejor que la del dispositivo). */
    static final Map<String, Double> SCALES = new LinkedHashMap<>();

    static {
        SCALES.put("TIME", 1.0); // s
        SCALES.put("LATITUDE", 1e7); // 1e-7° ≈ 1.1 cm
        SCALES.put("LONGITUDE", 1e7);
        SCALES.put("ALTITUDE", 10.0); // 0.1 m (FIT: 0.2 m)
        SCALES.put("DISTANCE", 100.0); // 1 cm (FIT: 1 cm)
        SCALES.put("SPEED", 1000.0); // 1 mm/s (FIT: 1 mm/s)
        SCALES.put("HEART_RATE", 1.0);
        SCALES.put("CADENCE", 1.0);
        SCALES.put("POWER", 1.0);
        SCALES.put("TEMPERATURE", 1.0); // °C (FIT: 1 °C)
    }

    @Autowired
    DataSource dataSource;

    @TempDir
    Path files;

    record Result(String option, long bytes, double writeMs, double readAllMs, double readOneMs, String notes) {}

    @Test
    void compareStorageOptions() throws Exception {
        var activities = new ArrayList<Map<String, double[]>>();
        var random = new Random(42);
        for (int i = 0; i < ACTIVITIES; i++) {
            activities.add(SyntheticRide.generate(random));
        }
        var ids = activities.stream().map(a -> UUID.randomUUID()).toList();
        var results = new ArrayList<Result>();

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            results.add(arrays(connection, ids, activities, "pglz"));
            results.add(arrays(connection, ids, activities, "lz4"));
            results.add(jsonb(connection, ids, activities));
            results.add(binary(connection, ids, activities));
        }
        results.add(externalFiles(ids, activities));

        var report = report(results);
        System.out.println(report);
        var out = Path.of("build", "benchmark", "streams.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        assertThat(results).hasSize(5);
    }

    // ---- A: float8[] por stream ---------------------------------------------------------------------------------

    private Result arrays(Connection c, List<UUID> ids, List<Map<String, double[]>> data, String compression)
            throws SQLException {
        var table = "bench_arrays_" + compression;
        exec(c, "DROP TABLE IF EXISTS " + table);
        exec(
                c,
                "CREATE TABLE " + table + " (activity_id uuid, stream_type text, v float8[] COMPRESSION " + compression
                        + ", PRIMARY KEY (activity_id, stream_type))");
        var write = time(() -> {
            try (var ps = c.prepareStatement("INSERT INTO " + table + " VALUES (?, ?, ?)")) {
                for (int i = 0; i < ids.size(); i++) {
                    for (var stream : data.get(i).entrySet()) {
                        ps.setObject(1, ids.get(i));
                        ps.setString(2, stream.getKey());
                        ps.setArray(3, c.createArrayOf("float8", boxed(stream.getValue())));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        });
        var readAll = timeReads(ids, id -> {
            try (var ps = c.prepareStatement("SELECT stream_type, v FROM " + table + " WHERE activity_id = ?")) {
                ps.setObject(1, id);
                try (var rs = ps.executeQuery()) {
                    var n = 0;
                    while (rs.next()) {
                        n += ((Double[]) rs.getArray(2).getArray()).length;
                    }
                    return n;
                }
            }
        });
        var readOne = timeReads(ids, id -> {
            try (var ps = c.prepareStatement(
                    "SELECT v FROM " + table + " WHERE activity_id = ? AND stream_type = 'ALTITUDE'")) {
                ps.setObject(1, id);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return ((Double[]) rs.getArray(1).getArray()).length;
                }
            }
        });
        var bytes = size(c, table);
        exec(c, "DROP TABLE " + table);
        return new Result("float8[] (" + compression + ")", bytes, write, readAll, readOne, "sin pérdida; SQL nativo");
    }

    // ---- B: JSONB por stream ------------------------------------------------------------------------------------

    private Result jsonb(Connection c, List<UUID> ids, List<Map<String, double[]>> data) throws SQLException {
        var table = "bench_jsonb";
        exec(c, "DROP TABLE IF EXISTS " + table);
        exec(
                c,
                "CREATE TABLE " + table + " (activity_id uuid, stream_type text, v jsonb, "
                        + "PRIMARY KEY (activity_id, stream_type))");
        var write = time(() -> {
            try (var ps = c.prepareStatement("INSERT INTO " + table + " VALUES (?, ?, ?::jsonb)")) {
                for (int i = 0; i < ids.size(); i++) {
                    for (var stream : data.get(i).entrySet()) {
                        ps.setObject(1, ids.get(i));
                        ps.setString(2, stream.getKey());
                        ps.setString(3, json(stream.getValue()));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        });
        var readAll = timeReads(ids, id -> {
            try (var ps = c.prepareStatement("SELECT v::text FROM " + table + " WHERE activity_id = ?")) {
                ps.setObject(1, id);
                try (var rs = ps.executeQuery()) {
                    var n = 0;
                    while (rs.next()) {
                        n += parseJson(rs.getString(1)).length;
                    }
                    return n;
                }
            }
        });
        var readOne = timeReads(ids, id -> {
            try (var ps = c.prepareStatement(
                    "SELECT v::text FROM " + table + " WHERE activity_id = ? AND stream_type = 'ALTITUDE'")) {
                ps.setObject(1, id);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return parseJson(rs.getString(1)).length;
                }
            }
        });
        var bytes = size(c, table);
        exec(c, "DROP TABLE " + table);
        return new Result("JSONB", bytes, write, readAll, readOne, "sin pérdida; consultable con operadores JSON");
    }

    // ---- C: bytea con delta + varint + deflate ------------------------------------------------------------------

    private Result binary(Connection c, List<UUID> ids, List<Map<String, double[]>> data) throws SQLException {
        var table = "bench_binary";
        exec(c, "DROP TABLE IF EXISTS " + table);
        // EXTERNAL: TOAST sin volver a comprimir datos ya comprimidos.
        exec(
                c,
                "CREATE TABLE " + table + " (activity_id uuid, stream_type text, v bytea STORAGE EXTERNAL, "
                        + "PRIMARY KEY (activity_id, stream_type))");
        var write = time(() -> {
            try (var ps = c.prepareStatement("INSERT INTO " + table + " VALUES (?, ?, ?)")) {
                for (int i = 0; i < ids.size(); i++) {
                    for (var stream : data.get(i).entrySet()) {
                        ps.setObject(1, ids.get(i));
                        ps.setString(2, stream.getKey());
                        ps.setBytes(3, Codec.encode(stream.getValue(), SCALES.get(stream.getKey())));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        });
        var readAll = timeReads(ids, id -> {
            try (var ps = c.prepareStatement("SELECT stream_type, v FROM " + table + " WHERE activity_id = ?")) {
                ps.setObject(1, id);
                try (var rs = ps.executeQuery()) {
                    var n = 0;
                    while (rs.next()) {
                        n += Codec.decode(rs.getBytes(2), SCALES.get(rs.getString(1))).length;
                    }
                    return n;
                }
            }
        });
        var readOne = timeReads(ids, id -> {
            try (var ps = c.prepareStatement(
                    "SELECT v FROM " + table + " WHERE activity_id = ? AND stream_type = 'ALTITUDE'")) {
                ps.setObject(1, id);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return Codec.decode(rs.getBytes(1), SCALES.get("ALTITUDE")).length;
                }
            }
        });
        // Verificación: el error de cuantización no supera media unidad de la escala.
        var sample = data.getFirst().get("LATITUDE");
        var roundTrip = Codec.decode(Codec.encode(sample, 1e7), 1e7);
        for (int i = 0; i < sample.length; i++) {
            assertThat(Math.abs(sample[i] - roundTrip[i])).isLessThanOrEqualTo(0.5e-7 + 1e-12);
        }
        var bytes = size(c, table);
        exec(c, "DROP TABLE " + table);
        return new Result(
                "bytea (delta+varint+deflate)",
                bytes,
                write,
                readAll,
                readOne,
                "cuantizado a resolución de dispositivo");
    }

    // ---- D: archivo externo por actividad -----------------------------------------------------------------------

    private Result externalFiles(List<UUID> ids, List<Map<String, double[]>> data) {
        var write = time(() -> {
            for (int i = 0; i < ids.size(); i++) {
                try (var out = new DataOutputStream(Files.newOutputStream(files.resolve(ids.get(i) + ".bin")))) {
                    for (var stream : data.get(i).entrySet()) {
                        var encoded = Codec.encode(stream.getValue(), SCALES.get(stream.getKey()));
                        out.writeUTF(stream.getKey());
                        out.writeInt(encoded.length);
                        out.write(encoded);
                    }
                }
            }
        });
        var readAll = timeReads(ids, id -> readFile(id, null));
        var readOne = timeReads(ids, id -> readFile(id, "ALTITUDE"));
        long bytes = 0;
        for (var id : ids) {
            try {
                bytes += Files.size(files.resolve(id + ".bin"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new Result(
                "archivo externo (mismo códec)",
                bytes,
                write,
                readAll,
                readOne,
                "sin transacción con la BD; backup aparte");
    }

    private int readFile(UUID id, String only) throws IOException {
        try (var in = new DataInputStream(Files.newInputStream(files.resolve(id + ".bin")))) {
            var n = 0;
            while (in.available() > 0) {
                var type = in.readUTF();
                var encoded = in.readNBytes(in.readInt());
                if (only == null || only.equals(type)) {
                    n += Codec.decode(encoded, SCALES.get(type)).length;
                }
            }
            return n;
        }
    }

    // ---- utilidades ---------------------------------------------------------------------------------------------

    @FunctionalInterface
    interface Work {
        void run() throws Exception;
    }

    @FunctionalInterface
    interface Read {
        int apply(UUID id) throws Exception;
    }

    private static double time(Work work) {
        var start = System.nanoTime();
        try {
            work.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return (System.nanoTime() - start) / 1e6;
    }

    /** Promedio en ms tras calentar; verifica que cada lectura devuelve datos. */
    private static double timeReads(List<UUID> ids, Read read) {
        var random = new Random(7);
        try {
            for (int i = 0; i < 10; i++) {
                read.apply(ids.get(random.nextInt(ids.size())));
            }
            var start = System.nanoTime();
            for (int i = 0; i < READS; i++) {
                assertThat(read.apply(ids.get(random.nextInt(ids.size())))).isGreaterThanOrEqualTo(SAMPLES);
            }
            return (System.nanoTime() - start) / 1e6 / READS;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static long size(Connection c, String table) throws SQLException {
        try (var st = c.createStatement();
                var rs = st.executeQuery("SELECT pg_total_relation_size('" + table + "')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Double[] boxed(double[] values) {
        return Arrays.stream(values).boxed().toArray(Double[]::new);
    }

    private static String json(double[] values) {
        var sb = new StringBuilder(values.length * 10).append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }

    private static double[] parseJson(String json) {
        var body = json.substring(1, json.length() - 1);
        return Arrays.stream(body.split(","))
                .mapToDouble(s -> Double.parseDouble(s.strip()))
                .toArray();
    }

    private static String report(List<Result> results) {
        var sb = new StringBuilder();
        sb.append("# Benchmark de almacenamiento de streams\n\n");
        sb.append(String.format(
                Locale.ROOT,
                "%d actividades sintéticas de 3 h a 1 Hz, 10 streams (%,d valores por actividad). "
                        + "Lecturas: promedio de %d actividades al azar tras calentar.%n%n",
                ACTIVITIES,
                SAMPLES * 10,
                READS));
        sb.append(
                "| Opción | Espacio total | Por actividad | Escritura total | Leer actividad | Leer 1 stream | Notas |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (var r : results) {
            sb.append(String.format(
                    Locale.ROOT,
                    "| %s | %.1f MiB | %.0f KiB | %.0f ms | %.2f ms | %.2f ms | %s |%n",
                    r.option(),
                    r.bytes() / 1048576.0,
                    r.bytes() / 1024.0 / ACTIVITIES,
                    r.writeMs(),
                    r.readAllMs(),
                    r.readOneMs(),
                    r.notes()));
        }
        return sb.toString();
    }

    /** Códec binario: cuantiza, codifica diferencias con zigzag + varint y comprime con deflate. */
    static final class Codec {

        static byte[] encode(double[] values, double scale) {
            var bytes = new ByteArrayOutputStream();
            try (OutputStream out = new BufferedOutputStream(
                    new DeflaterOutputStream(bytes, new Deflater(Deflater.BEST_SPEED)), 8192)) {
                long previous = 0;
                for (var value : values) {
                    var quantized = Math.round(value * scale);
                    writeVarLong(out, zigzag(quantized - previous));
                    previous = quantized;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return bytes.toByteArray();
        }

        static double[] decode(byte[] encoded, double scale) {
            var values = new double[SAMPLES + 16];
            var n = 0;
            try (InputStream in =
                    new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(encoded)), 8192)) {
                long previous = 0;
                long raw;
                while ((raw = readVarLong(in)) != Long.MIN_VALUE) {
                    previous += unzigzag(raw);
                    if (n == values.length) {
                        values = Arrays.copyOf(values, n * 2);
                    }
                    values[n++] = previous / scale;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return Arrays.copyOf(values, n);
        }

        private static long zigzag(long v) {
            return (v << 1) ^ (v >> 63);
        }

        private static long unzigzag(long v) {
            return (v >>> 1) ^ -(v & 1);
        }

        private static void writeVarLong(OutputStream out, long v) throws IOException {
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
        }

        /** Devuelve {@link Long#MIN_VALUE} al final del stream. */
        private static long readVarLong(InputStream in) throws IOException {
            long result = 0;
            int shift = 0;
            int b;
            while ((b = in.read()) != -1) {
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            }
            return Long.MIN_VALUE;
        }
    }

    /** Rodada sintética con variaciones suaves y ruido realista de sensores. */
    static final class SyntheticRide {

        static Map<String, double[]> generate(Random random) {
            var streams = new LinkedHashMap<String, double[]>();
            for (var name : SCALES.keySet()) {
                streams.put(name, new double[SAMPLES]);
            }
            double lat = 19.0 + random.nextDouble();
            double lon = -99.5 + random.nextDouble();
            double heading = random.nextDouble() * Math.PI * 2;
            double altitude = 2200 + random.nextDouble() * 500;
            double distance = 0;
            double speed = 7;
            double heartRate = 110;
            double temperature = 14 + random.nextInt(8);
            for (int t = 0; t < SAMPLES; t++) {
                heading += random.nextGaussian() * 0.05;
                speed = clamp(speed + random.nextGaussian() * 0.15, 2, 16);
                lat += Math.cos(heading) * speed / 111_195.0 + random.nextGaussian() * 2e-6;
                lon += Math.sin(heading) * speed / (111_195.0 * Math.cos(Math.toRadians(lat)))
                        + random.nextGaussian() * 2e-6;
                altitude = clamp(altitude + random.nextGaussian() * 0.25, 1800, 3500);
                distance += speed;
                heartRate = clamp(heartRate + random.nextGaussian() * 0.8, 95, 185);
                if (t % 600 == 0) {
                    temperature += random.nextInt(3) - 1;
                }
                streams.get("TIME")[t] = t;
                streams.get("LATITUDE")[t] = lat;
                streams.get("LONGITUDE")[t] = lon;
                streams.get("ALTITUDE")[t] = Math.round(altitude * 5) / 5.0;
                streams.get("DISTANCE")[t] = Math.round(distance * 100) / 100.0;
                streams.get("SPEED")[t] = Math.round(speed * 1000) / 1000.0;
                streams.get("HEART_RATE")[t] = Math.round(heartRate);
                streams.get("CADENCE")[t] = Math.max(0, Math.round(80 + random.nextGaussian() * 6));
                streams.get("POWER")[t] = Math.max(0, Math.round(180 + random.nextGaussian() * 45));
                streams.get("TEMPERATURE")[t] = temperature;
            }
            return streams;
        }

        private static double clamp(double v, double min, double max) {
            return Math.max(min, Math.min(max, v));
        }
    }
}
