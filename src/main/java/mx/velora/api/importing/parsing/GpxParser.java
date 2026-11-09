package mx.velora.api.importing.parsing;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import mx.velora.api.importing.FileFormat;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.ImportedActivity;
import mx.velora.api.importing.ParserInfo;
import mx.velora.api.importing.RejectionReason;
import mx.velora.api.importing.TrackPoint;
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
        var points = new ArrayList<TrackPoint>();
        String title = null;
        int segment = -1;
        PointBuilder current = null;

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
                        current = new PointBuilder(
                                Math.max(segment, 0),
                                XmlValues.requiredDouble(reader.getAttributeValue(null, "lat"), "lat", reader),
                                XmlValues.requiredDouble(reader.getAttributeValue(null, "lon"), "lon", reader));
                    }
                    path.push(name);
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    path.pop();
                    if (current != null && reader.getLocalName().equals("trkpt")) {
                        if (points.size() >= limits.maxPoints()) {
                            throw new ImportRejectedException(
                                    RejectionReason.TOO_MANY_POINTS,
                                    "el archivo supera " + limits.maxPoints() + " muestras");
                        }
                        points.add(current.build());
                        current = null;
                    }
                }
                default -> {
                    // Texto entre elementos, comentarios e instrucciones de proceso: sin información útil.
                }
            }
        }

        var startedAt = points.stream()
                .map(TrackPoint::time)
                .filter(time -> time != null)
                .findFirst()
                .orElse(null);
        return new ImportedActivity(FileFormat.GPX, INFO, null, title, startedAt, List.copyOf(points));
    }

    /** Lee un campo hoja de {@code trkpt}. Devuelve {@code false} si el elemento no es un campo conocido. */
    private static boolean readPointField(XMLStreamReader reader, String name, PointBuilder point)
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

    private static final class PointBuilder {
        private final int segment;
        private final double latitude;
        private final double longitude;
        private @Nullable Instant time;
        private @Nullable Double altitudeMeters;
        private @Nullable Integer heartRateBpm;
        private @Nullable Integer cadenceRpm;
        private @Nullable Integer powerWatts;
        private @Nullable Double speedMps;
        private @Nullable Double temperatureCelsius;

        private PointBuilder(int segment, double latitude, double longitude) {
            this.segment = segment;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        private TrackPoint build() {
            return new TrackPoint(
                    segment,
                    time,
                    latitude,
                    longitude,
                    altitudeMeters,
                    null,
                    heartRateBpm,
                    cadenceRpm,
                    powerWatts,
                    speedMps,
                    temperatureCelsius);
        }
    }
}
