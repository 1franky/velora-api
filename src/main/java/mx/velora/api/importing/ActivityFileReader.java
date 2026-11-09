package mx.velora.api.importing;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import mx.velora.api.importing.parsing.ActivityFileParser;
import mx.velora.api.importing.parsing.FileFormatDetector;
import mx.velora.api.importing.parsing.GpxParser;
import mx.velora.api.importing.parsing.ParserLimits;

/** Punto de entrada del módulo para leer un archivo de actividad: detecta el formato y delega en su parser. */
public final class ActivityFileReader {

    private final Map<FileFormat, ActivityFileParser> parsers = new EnumMap<>(FileFormat.class);

    public ActivityFileReader(List<ActivityFileParser> parsers) {
        for (var parser : parsers) {
            if (this.parsers.put(parser.format(), parser) != null) {
                throw new IllegalArgumentException("Parser duplicado para " + parser.format());
            }
        }
    }

    public static ActivityFileReader withDefaultParsers(ParserLimits limits) {
        return new ActivityFileReader(List.of(new GpxParser(limits)));
    }

    /**
     * Lee el archivo completo. No cierra {@code content}.
     *
     * @throws ImportRejectedException si el archivo está vacío, no se reconoce, es inválido o supera los límites
     * @throws IOException si falla la lectura del stream
     */
    public ImportedActivity read(InputStream content) throws IOException {
        var detection = FileFormatDetector.detect(content);
        var parser = parsers.get(detection.format());
        if (parser == null) {
            throw new ImportRejectedException(
                    RejectionReason.UNSUPPORTED_FORMAT, "el formato " + detection.format() + " aún no está soportado");
        }
        return parser.parse(detection.content());
    }
}
