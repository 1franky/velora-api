package mx.velora.api.importing.parsing;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.List;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import mx.velora.api.importing.FileFormat;
import mx.velora.api.importing.ImportedActivity;
import mx.velora.api.importing.ParserInfo;
import org.jspecify.annotations.Nullable;

/**
 * Parser GPX 1.0/1.1 en streaming.
 *
 * <p>Lee {@code trk/trkseg/trkpt} con {@code ele} y {@code time}, y los sensores de las extensiones habituales
 * (Garmin TrackPointExtension v1/v2 y similares): {@code hr}, {@code cad}, {@code atemp}, {@code power} y
 * {@code speed}, identificados por nombre local sin importar el namespace. Ignora rutas ({@code rte}) y waypoints
 * ({@code wpt}), que no representan una actividad registrada.
 */
public final class GpxParser implements ActivityFileParser {

    static final ParserInfo INFO = new ParserInfo("velora-gpx", "1");

    private final ParserLimits limits;

    public GpxParser(ParserLimits limits) {
        this.limits = limits;
    }

    @Override
    public FileFormat format() {
        return FileFormat.GPX;
    }

    @Override
    public ImportedActivity parse(InputStream content) {
        var reader = SafeXml.createReader(content);
        try {
            return read(reader);
        } catch (XMLStreamException e) {
            throw SafeXml.malformed(e);
        } finally {
            SafeXml.closeQuietly(reader);
        }
    }

    private ImportedActivity read(XMLStreamReader reader) throws XMLStreamException {
        var path = new ArrayDeque<String>();
        var points = new PointCollector(limits);
        String title = null;
        int segment = -1;
        TrackPointBuilder current = null;

        while (reader.hasNext()) {
            switch (reader.next()) {
                case XMLStreamConstants.DTD -> throw SafeXml.rejectDtd();
                case XMLStreamConstants.START_ELEMENT -> {
                    var name = reader.getLocalName();
                    if (current != null && readPointField(reader, name, current)) {
                        continue; // Elemento hoja consumido por getElementText().
                    }
                    if (current == null && name.equals("name") && "trk".equals(path.peek()) && title == null) {
                        title = blankToNull(reader.getElementText());
                        continue;
                    }
                    if (name.equals("trkseg") && current == null) {
                        segment++;
                    } else if (name.equals("trkpt") && current == null) {
                        current = new TrackPointBuilder(Math.max(segment, 0));
                        current.latitude =
                                XmlValues.requiredDouble(reader.getAttributeValue(null, "lat"), "lat", reader);
                        current.longitude =
                                XmlValues.requiredDouble(reader.getAttributeValue(null, "lon"), "lon", reader);
                    }
                    path.push(name);
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    path.pop();
                    if (current != null && reader.getLocalName().equals("trkpt")) {
                        points.add(current.build());
                        current = null;
                    }
                }
                default -> {
                    // Texto entre elementos, comentarios e instrucciones de proceso: sin información útil.
                }
            }
        }

        return new ImportedActivity(FileFormat.GPX, INFO, null, title, points.firstTime(), List.of(), points.points());
    }

    /** Lee un campo hoja de {@code trkpt}. Devuelve {@code false} si el elemento no es un campo conocido. */
    private static boolean readPointField(XMLStreamReader reader, String name, TrackPointBuilder point)
            throws XMLStreamException {
        switch (name) {
            case "ele" -> point.altitudeMeters = XmlValues.optionalDouble(reader.getElementText(), name, reader);
            case "time" -> point.time = XmlValues.optionalInstant(reader.getElementText(), name, reader);
            case "hr" -> point.heartRateBpm = XmlValues.optionalInteger(reader.getElementText(), name, reader);
            case "cad" -> point.cadenceRpm = XmlValues.optionalInteger(reader.getElementText(), name, reader);
            case "power" -> point.powerWatts = XmlValues.optionalInteger(reader.getElementText(), name, reader);
            case "atemp" -> point.temperatureCelsius = XmlValues.optionalDouble(reader.getElementText(), name, reader);
            case "speed" -> point.speedMps = XmlValues.optionalDouble(reader.getElementText(), name, reader);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static @Nullable String blankToNull(String text) {
        var stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
