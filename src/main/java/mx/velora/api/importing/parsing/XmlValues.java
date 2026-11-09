package mx.velora.api.importing.parsing;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import javax.xml.stream.XMLStreamReader;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.RejectionReason;
import org.jspecify.annotations.Nullable;

/**
 * Conversión de textos XML a valores tipados.
 *
 * <p>Los errores indican solo el campo y la línea; nunca el valor leído, que puede ser una coordenada.
 */
final class XmlValues {

    private XmlValues() {}

    static @Nullable Double optionalDouble(@Nullable String text, String field, XMLStreamReader reader) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            var value = Double.parseDouble(text.strip());
            if (!Double.isFinite(value)) {
                throw invalid(field, reader);
            }
            return value;
        } catch (NumberFormatException e) {
            throw invalid(field, reader);
        }
    }

    static double requiredDouble(@Nullable String text, String field, XMLStreamReader reader) {
        var value = optionalDouble(text, field, reader);
        if (value == null) {
            throw new ImportRejectedException(
                    RejectionReason.MALFORMED_FILE, "falta '" + field + "' cerca de la línea " + line(reader));
        }
        return value;
    }

    /** Acepta enteros escritos como decimales ("142.0"), comunes en algunos exportadores. */
    static @Nullable Integer optionalInteger(@Nullable String text, String field, XMLStreamReader reader) {
        var value = optionalDouble(text, field, reader);
        if (value == null) {
            return null;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid(field, reader);
        }
        return (int) Math.round(value);
    }

    /**
     * Interpreta un {@code xsd:dateTime}. Sin zona horaria se asume UTC, como exige GPX 1.1 y TCX.
     */
    static @Nullable Instant optionalInstant(@Nullable String text, String field, XMLStreamReader reader) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            var parsed =
                    DateTimeFormatter.ISO_DATE_TIME.parseBest(text.strip(), OffsetDateTime::from, LocalDateTime::from);
            return parsed instanceof OffsetDateTime offset
                    ? offset.toInstant()
                    : ((LocalDateTime) parsed).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw invalid(field, reader);
        }
    }

    private static ImportRejectedException invalid(String field, XMLStreamReader reader) {
        return new ImportRejectedException(
                RejectionReason.MALFORMED_FILE, "valor inválido en '" + field + "' cerca de la línea " + line(reader));
    }

    private static int line(XMLStreamReader reader) {
        var location = reader.getLocation();
        return location != null ? location.getLineNumber() : -1;
    }
}
