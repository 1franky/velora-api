package mx.velora.api.importing;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Actividad leída de un archivo, antes de validación de dominio, normalización y persistencia.
 *
 * @param format formato detectado del archivo
 * @param parser parser y versión que la produjeron
 * @param sourceActivityKey clave estable de la actividad en su origen, si el formato la provee (ADR-009)
 * @param title nombre de la actividad incluido en el archivo, si existe
 * @param startedAt instante UTC de la primera muestra con tiempo o, si no hay, el inicio declarado por el archivo;
 *     {@code null} si el archivo no tiene tiempos
 * @param laps vueltas reportadas por el dispositivo; vacía si el formato no las tiene
 * @param points muestras en el orden del archivo
 * @param reported totales informados por el dispositivo; {@link ReportedSummary#EMPTY} si el formato no los tiene
 */
public record ImportedActivity(
        FileFormat format,
        ParserInfo parser,
        @Nullable String sourceActivityKey,
        @Nullable String title,
        @Nullable Instant startedAt,
        List<ImportedLap> laps,
        List<TrackPoint> points,
        ReportedSummary reported) {

    public ImportedActivity {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(parser, "parser");
        laps = List.copyOf(laps);
        points = List.copyOf(points);
        Objects.requireNonNull(reported, "reported");
    }
}
