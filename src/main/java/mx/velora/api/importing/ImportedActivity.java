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
 * @param sourceActivityKey clave estable de la actividad en su origen, si el formato la provee
 * @param title nombre de la actividad incluido en el archivo, si existe
 * @param startedAt instante UTC de la primera muestra con tiempo; {@code null} si el archivo no tiene tiempos
 * @param points muestras en el orden del archivo
 */
public record ImportedActivity(
        FileFormat format,
        ParserInfo parser,
        @Nullable String sourceActivityKey,
        @Nullable String title,
        @Nullable Instant startedAt,
        List<TrackPoint> points) {

    public ImportedActivity {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(parser, "parser");
        points = List.copyOf(points);
    }
}
