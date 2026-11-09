package mx.velora.api.importing;

import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import mx.velora.api.importing.DerivedMetrics.Parameters;
import mx.velora.api.importing.derive.DerivedMetricsCalculator;
import mx.velora.api.importing.parsing.ParserLimits;
import mx.velora.api.importing.validation.ActivityValidator;
import mx.velora.api.importing.validation.DuplicateHeuristic;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Analiza archivos reales del usuario <b>solo en local</b> para calibrar umbrales. Nunca imprime coordenadas, nombres
 * ni contenido: solo conteos, totales y comparaciones. Los archivos no se versionan.
 *
 * <p>{@code ./gradlew analyzeLocalFiles -PlocalFiles=/ruta/a/carpeta}. Resultado también en
 * {@code build/local-data/report.md} (fuera de Git).
 */
@Tag("local-data")
class LocalFilesReport {

    private static final double[] HYSTERESIS = {0, 1, 2, 3, 5, 10};
    private static final double[] MOVING_THRESHOLDS = {0.5, 1.0, 1.5};

    private record Analyzed(
            String name, String sha256, @Nullable ValidatedActivity activity, String error) {}

    @Test
    void report() throws Exception {
        var dir = System.getProperty("velora.localFiles");
        assumeThat(dir).as("define -PlocalFiles=<carpeta>").isNotBlank();
        var files =
                Files.list(Path.of(dir)).filter(Files::isRegularFile).sorted().toList();
        var reader = ActivityFileReader.withDefaultParsers(ParserLimits.DEFAULT);
        var validator = new ActivityValidator(Clock.systemUTC());
        var analyzed = new ArrayList<Analyzed>();
        for (var file : files) {
            var sha = sha256(file);
            try (var in = Files.newInputStream(file)) {
                analyzed.add(new Analyzed(file.getFileName().toString(), sha, validator.validate(reader.read(in)), ""));
            } catch (ImportRejectedException e) {
                analyzed.add(new Analyzed(file.getFileName().toString(), sha, null, e.getMessage()));
            }
        }

        var out = new StringBuilder("# Análisis local de archivos reales\n\n");
        overview(out, analyzed);
        metrics(out, analyzed);
        elevation(out, analyzed);
        moving(out, analyzed);
        duplicates(out, analyzed);
        diagnostics(out, analyzed);
        discontinuities(out, analyzed);
        altitudeSteps(out, analyzed);

        System.out.println(out);
        var target = Path.of("build", "local-data", "report.md");
        Files.createDirectories(target.getParent());
        Files.writeString(target, out);
    }

    private static void overview(StringBuilder out, List<Analyzed> all) {
        out.append(
                "## Lectura y calidad\n\n| Archivo | Formato | SHA-256 | Muestras | Seg. | Vueltas | % pos | % alt | % FC | % pot | % cad | Advertencias | Inicio local |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (var a : all) {
            if (a.activity() == null) {
                out.append("| %s | — | %s | rechazado: %s |||||||||||%n".formatted(a.name(), a.sha256(), a.error()));
                continue;
            }
            var act = a.activity().activity();
            var p = act.points();
            out.append(String.format(
                    Locale.ROOT,
                    "| %s | %s | %s | %d | %d | %d | %.0f | %.0f | %.0f | %.0f | %.0f | %s | %s |%n",
                    a.name(),
                    act.format(),
                    a.sha256(),
                    p.size(),
                    p.isEmpty() ? 0 : p.getLast().segment() + 1,
                    act.laps().size(),
                    pct(p, TrackPoint::hasPosition),
                    pct(p, t -> t.altitudeMeters() != null),
                    pct(p, t -> t.heartRateBpm() != null),
                    pct(p, t -> t.powerWatts() != null),
                    pct(p, t -> t.cadenceRpm() != null),
                    a.activity().warnings().isEmpty()
                            ? "—"
                            : a.activity().warnings().toString(),
                    a.activity().footprint().localStartDate(ZoneId.of("America/Mexico_City"))));
        }
    }

    private static void metrics(StringBuilder out, List<Analyzed> all) {
        out.append("\n## Reportado por el dispositivo vs. derivado (histéresis 3 m, umbral 1 m/s)\n\n");
        out.append(
                "| Archivo | Deporte | Dist. rep. (km) | Dist. der. (km) | Cronómetro rep. | Movimiento der. | "
                        + "Transcurrido der. | Vel. máx rep. (km/h) | Vel. máx der. | FC media rep./der. | Pot. media rep./der. |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (var a : all) {
            if (a.activity() == null) {
                continue;
            }
            var act = a.activity().activity();
            var r = act.reported();
            var d = DerivedMetricsCalculator.compute(act, params(1.0, 3));
            out.append(String.format(
                    Locale.ROOT,
                    "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s/%s | %s/%s |%n",
                    a.name(),
                    r.sport() == null ? "—" : r.sport() + (r.subSport() == null ? "" : "/" + r.subSport()),
                    km(r.distanceMeters()),
                    km(d.distanceMeters()),
                    hms(r.timerTimeSeconds()),
                    hms(d.movingTimeSeconds()),
                    hms(d.elapsedTimeSeconds()),
                    kmh(r.maxSpeedMps()),
                    kmh(d.maxSpeedMps()),
                    nz(r.averageHeartRateBpm()),
                    nz(d.averageHeartRateBpm()),
                    nz(r.averagePowerWatts()),
                    nz(d.averagePowerWatts())));
        }
    }

    private static void elevation(StringBuilder out, List<Analyzed> all) {
        out.append("\n## Desnivel positivo según histéresis (m)\n\n| Archivo | Reportado |");
        for (var h : HYSTERESIS) {
            out.append(String.format(Locale.ROOT, " H=%.0f |", h));
        }
        out.append("\n|---|---|").append("---|".repeat(HYSTERESIS.length)).append('\n');
        for (var a : all) {
            if (a.activity() == null) {
                continue;
            }
            var act = a.activity().activity();
            out.append("| ")
                    .append(a.name())
                    .append(" | ")
                    .append(num(act.reported().ascentMeters()))
                    .append(" |");
            for (var h : HYSTERESIS) {
                out.append(' ')
                        .append(num(DerivedMetricsCalculator.compute(act, params(1.0, h))
                                .ascentMeters()))
                        .append(" |");
            }
            out.append('\n');
        }
    }

    private static void moving(StringBuilder out, List<Analyzed> all) {
        out.append("\n## Tiempo en movimiento según umbral\n\n| Archivo | Cronómetro rep. |");
        for (var m : MOVING_THRESHOLDS) {
            out.append(String.format(Locale.ROOT, " %.1f m/s |", m));
        }
        out.append("\n|---|---|")
                .append("---|".repeat(MOVING_THRESHOLDS.length))
                .append('\n');
        for (var a : all) {
            if (a.activity() == null) {
                continue;
            }
            var act = a.activity().activity();
            out.append("| ")
                    .append(a.name())
                    .append(" | ")
                    .append(hms(act.reported().timerTimeSeconds()))
                    .append(" |");
            for (var m : MOVING_THRESHOLDS) {
                out.append(' ')
                        .append(hms(DerivedMetricsCalculator.compute(act, params(m, 3))
                                .movingTimeSeconds()))
                        .append(" |");
            }
            out.append('\n');
        }
    }

    private static void duplicates(StringBuilder out, List<Analyzed> all) {
        out.append("\n## Duplicados\n\n");
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                var a = all.get(i);
                var b = all.get(j);
                if (a.sha256().equals(b.sha256())) {
                    out.append("- **Exacto (SHA-256):** %s = %s%n".formatted(a.name(), b.name()));
                } else if (a.activity() != null
                        && b.activity() != null
                        && DuplicateHeuristic.isLikelyDuplicate(
                                a.activity().footprint(), b.activity().footprint())) {
                    out.append("- **Heurístico:** %s ≈ %s%n".formatted(a.name(), b.name()));
                }
            }
        }
    }

    /** Diagnósticos agregados: saltos de tiempo, ruido de altitud y velocidad máxima según ventana. */
    private static void diagnostics(StringBuilder out, List<Analyzed> all) {
        out.append("\n## Diagnóstico de tiempos y altitud\n\n");
        out.append(
                "| Archivo | Mayor salto de tiempo | Índice del salto | Tiempos que retroceden | |Δalt| mediana | p99 | máx | "
                        + "saltos alt >20 m | Vel. máx 5/10/30 s (km/h) |\n|---|---|---|---|---|---|---|---|---|\n");
        for (var a : all) {
            if (a.activity() == null) {
                continue;
            }
            var p = a.activity().activity().points();
            double maxGap = 0;
            int gapIndex = -1;
            int backwards = 0;
            var deltas = new ArrayList<Double>();
            Double previousAlt = null;
            java.time.Instant previousTime = null;
            for (int i = 0; i < p.size(); i++) {
                var point = p.get(i);
                if (point.time() != null) {
                    if (previousTime != null) {
                        var gap = java.time.Duration.between(previousTime, point.time())
                                        .toMillis()
                                / 1000.0;
                        if (gap < 0) {
                            backwards++;
                        }
                        if (Math.abs(gap) > Math.abs(maxGap)) {
                            maxGap = gap;
                            gapIndex = i;
                        }
                    }
                    previousTime = point.time();
                }
                if (point.altitudeMeters() != null) {
                    if (previousAlt != null) {
                        deltas.add(Math.abs(point.altitudeMeters() - previousAlt));
                    }
                    previousAlt = point.altitudeMeters();
                }
            }
            deltas.sort(Double::compare);
            var act = a.activity().activity();
            out.append(String.format(
                    Locale.ROOT,
                    "| %s | %s | %d de %d | %d | %.1f | %.1f | %.0f | %d | %s / %s / %s |%n",
                    a.name(),
                    hms(maxGap),
                    gapIndex,
                    p.size(),
                    backwards,
                    deltas.isEmpty() ? 0 : deltas.get(deltas.size() / 2),
                    deltas.isEmpty() ? 0 : deltas.get((int) (deltas.size() * 0.99)),
                    deltas.isEmpty() ? 0 : deltas.getLast(),
                    deltas.stream().filter(d -> d > 20).count(),
                    kmh(DerivedMetricsCalculator.compute(act, new Parameters(1, 300, 3, 5))
                            .maxSpeedMps()),
                    kmh(DerivedMetricsCalculator.compute(act, new Parameters(1, 300, 3, 10))
                            .maxSpeedMps()),
                    kmh(DerivedMetricsCalculator.compute(act, new Parameters(1, 300, 3, 30))
                            .maxSpeedMps())));
        }
    }

    /** Para saltos de tiempo de más de 6 h: contexto agregado (desplazamientos en horas y distancias, sin posiciones). */
    private static void discontinuities(StringBuilder out, List<Analyzed> all) {
        out.append("\n## Discontinuidades de más de 6 h\n\n");
        for (var a : all) {
            if (a.activity() == null) {
                continue;
            }
            var act = a.activity().activity();
            var p = act.points();
            var start = act.startedAt();
            for (int i = 1; i < p.size(); i++) {
                var before = p.get(i - 1);
                var after = p.get(i);
                if (before.time() == null || after.time() == null) {
                    continue;
                }
                var gapHours =
                        java.time.Duration.between(before.time(), after.time()).toMinutes() / 60.0;
                if (gapHours < 6) {
                    continue;
                }
                out.append(String.format(
                        Locale.ROOT,
                        "- %s: muestra %d → %d; antes %.2f h desde el inicio, después %.2f h; salto %.1f h; "
                                + "distancia del dispositivo %s → %s km; segmento %d → %d; última muestra a %.2f h%n",
                        a.name(),
                        i - 1,
                        i,
                        hoursBetween(start, before.time()),
                        hoursBetween(start, after.time()),
                        gapHours,
                        km(before.distanceMeters()),
                        km(after.distanceMeters()),
                        before.segment(),
                        after.segment(),
                        hoursBetween(start, p.getLast().time())));
                var r = act.reported();
                out.append(String.format(
                        Locale.ROOT,
                        "  - Sesión: transcurrido %s, cronómetro %s, distancia %s km. Vueltas que empiezan después del salto: %d de %d%n",
                        hms(r.elapsedTimeSeconds()),
                        hms(r.timerTimeSeconds()),
                        km(r.distanceMeters()),
                        act.laps().stream()
                                .filter(l ->
                                        l.startedAt() != null && !l.startedAt().isBefore(after.time()))
                                .count(),
                        act.laps().size()));
            }
        }
    }

    /** Saltos de altitud de más de 100 m: posición en la serie, duración del hueco y altitudes cercanas a 0 m. */
    private static void altitudeSteps(StringBuilder out, List<Analyzed> all) {
        out.append("\n## Saltos de altitud de más de 100 m\n\n");
        for (var a : all) {
            if (a.activity() == null) {
                continue;
            }
            var p = a.activity().activity().points();
            var nearZero = p.stream()
                    .filter(x -> x.altitudeMeters() != null && Math.abs(x.altitudeMeters()) < 5)
                    .count();
            TrackPoint previous = null;
            int previousIndex = -1;
            for (int i = 0; i < p.size(); i++) {
                var point = p.get(i);
                if (point.altitudeMeters() == null) {
                    continue;
                }
                if (previous != null && Math.abs(point.altitudeMeters() - previous.altitudeMeters()) > 100) {
                    out.append(String.format(
                            Locale.ROOT,
                            "- %s: muestra %d → %d de %d; Δ %+.0f m en %s; altitudes ≈0 m en el archivo: %d%n",
                            a.name(),
                            previousIndex,
                            i,
                            p.size(),
                            point.altitudeMeters() - previous.altitudeMeters(),
                            previous.time() == null || point.time() == null
                                    ? "?"
                                    : hms(java.time.Duration.between(previous.time(), point.time())
                                                    .toMillis()
                                            / 1000.0),
                            nearZero));
                }
                previous = point;
                previousIndex = i;
            }
        }
    }

    private static double hoursBetween(java.time.Instant from, java.time.Instant to) {
        return java.time.Duration.between(from, to).toMinutes() / 60.0;
    }

    private static Parameters params(double moving, double hysteresis) {
        return new Parameters(moving, 300, hysteresis, 10);
    }

    private static double pct(List<TrackPoint> points, Predicate<TrackPoint> predicate) {
        return points.isEmpty() ? 0 : 100.0 * points.stream().filter(predicate).count() / points.size();
    }

    private static String sha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String km(@Nullable Double meters) {
        return meters == null ? "—" : String.format(Locale.ROOT, "%.2f", meters / 1000);
    }

    private static String kmh(@Nullable Double mps) {
        return mps == null ? "—" : String.format(Locale.ROOT, "%.1f", mps * 3.6);
    }

    private static String num(@Nullable Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%.0f", value);
    }

    private static String nz(@Nullable Integer value) {
        return value == null ? "—" : value.toString();
    }

    private static String hms(@Nullable Double seconds) {
        if (seconds == null) {
            return "—";
        }
        var s = Math.round(seconds);
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
