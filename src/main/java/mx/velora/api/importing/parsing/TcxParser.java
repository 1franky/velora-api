package mx.velora.api.importing.parsing;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import mx.velora.api.importing.FileFormat;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.ImportedActivity;
import mx.velora.api.importing.ImportedLap;
import mx.velora.api.importing.ParserInfo;
import mx.velora.api.importing.RejectionReason;
import mx.velora.api.importing.ReportedSummary;
import org.jspecify.annotations.Nullable;

/**
 * Parser TCX (Garmin TrainingCenterDatabase v2) en streaming.
 *
 * <p>Lee una sola {@code Activity}: sus {@code Lap} con métricas de vuelta, y los {@code Trackpoint} con posición,
 * altitud, distancia, frecuencia cardíaca, cadencia y las extensiones {@code ActivityExtension/v2} ({@code Speed},
 * {@code Watts}; {@code AvgSpeed}, {@code AvgWatts}, {@code MaxWatts} en la vuelta). Cada {@code Track} es un
 * segmento: varios {@code Track} dentro de una vuelta indican pausas. Los {@code Trackpoint} sin {@code Position}
 * (p. ej. rodillo) conservan latitud y longitud en {@code null}.
 *
 * <p>Ignora {@code Courses} (rutas planificadas).
 *
 * <p>La clave de origen combina {@code Creator/UnitId} (si existe) con {@code Activity/Id}; es una señal débil de
 * deduplicación (ADR-009).
 */
public final class TcxParser implements ActivityFileParser {

    static final ParserInfo INFO = new ParserInfo("velora-tcx", "1");

    private final ParserLimits limits;

    public TcxParser(ParserLimits limits) {
        this.limits = limits;
    }

    @Override
    public FileFormat format() {
        return FileFormat.TCX;
    }

    @Override
    public ImportedActivity parse(InputStream content) {
        var reader = SafeXml.createReader(content);
        try {
            return new Session(reader, limits).read();
        } catch (XMLStreamException e) {
            throw SafeXml.malformed(e);
        } finally {
            SafeXml.closeQuietly(reader);
        }
    }

    /** Estado de un parseo; una instancia por archivo. */
    private static final class Session {
        private final XMLStreamReader reader;
        private final Deque<String> path = new ArrayDeque<>();
        private final PointCollector points;
        private final List<ImportedLap> laps = new ArrayList<>();
        private int activityCount;
        private @Nullable String activityId;
        private @Nullable String unitId;
        private @Nullable String sport;
        private int segment = -1;
        private @Nullable LapBuilder lap;
        private @Nullable TrackPointBuilder point;

        private Session(XMLStreamReader reader, ParserLimits limits) {
            this.reader = reader;
            this.points = new PointCollector(limits);
        }

        private ImportedActivity read() throws XMLStreamException {
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.DTD -> throw SafeXml.rejectDtd();
                    case XMLStreamConstants.START_ELEMENT -> {
                        var name = reader.getLocalName();
                        if (!readLeaf(name)) {
                            startContainer(name);
                            path.push(name);
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> endContainer(path.pop());
                    default -> {
                        // Texto entre elementos, comentarios e instrucciones de proceso: sin información útil.
                    }
                }
            }
            var startedAt = points.firstTime();
            if (startedAt == null && !laps.isEmpty()) {
                startedAt = laps.getFirst().startedAt();
            }
            return new ImportedActivity(
                    FileFormat.TCX, INFO, sourceKey(), null, startedAt, laps, points.points(), summarizeLaps());
        }

        private void startContainer(String name) {
            // Solo se leen datos dentro de Activity; Courses son rutas planificadas, no actividades registradas.
            if (!name.equals("Activity") && !path.contains("Activity")) {
                return;
            }
            switch (name) {
                case "Activity" -> {
                    sport = blankToNull(
                            java.util.Objects.requireNonNullElse(reader.getAttributeValue(null, "Sport"), ""));
                    if (++activityCount > 1) {
                        throw new ImportRejectedException(
                                RejectionReason.UNSUPPORTED_FORMAT,
                                "el archivo contiene varias actividades; solo se admite una por archivo");
                    }
                }
                case "Lap" ->
                    lap = new LapBuilder(
                            laps.size() + 1,
                            XmlValues.optionalInstant(
                                    reader.getAttributeValue(null, "StartTime"), "StartTime", reader));
                case "Track" -> segment++;
                case "Trackpoint" -> point = new TrackPointBuilder(Math.max(segment, 0));
                default -> {
                    // Contenedor sin datos propios (Position, Extensions, HeartRateBpm, Creator...).
                }
            }
        }

        private void endContainer(String name) {
            switch (name) {
                case "Trackpoint" -> {
                    if (point != null) {
                        points.add(point.build());
                        point = null;
                    }
                }
                case "Lap" -> {
                    if (lap != null) {
                        laps.add(lap.build());
                        lap = null;
                    }
                }
                default -> {
                    // Sin acción al cerrar otros contenedores.
                }
            }
        }

        /** Lee un elemento hoja conocido según su contexto. Devuelve {@code false} si no lo es. */
        private boolean readLeaf(String name) throws XMLStreamException {
            var parent = path.peek();
            if (point != null) {
                return readPointLeaf(point, name, parent);
            }
            if (lap != null) {
                return readLapLeaf(lap, name, parent);
            }
            if (name.equals("Id") && "Activity".equals(parent)) {
                activityId = blankToNull(reader.getElementText());
                return true;
            }
            if (name.equals("UnitId") && "Creator".equals(parent) && path.contains("Activity")) {
                unitId = blankToNull(reader.getElementText());
                return true;
            }
            return false;
        }

        private boolean readPointLeaf(TrackPointBuilder p, String name, @Nullable String parent)
                throws XMLStreamException {
            switch (name) {
                case "Time" -> p.time = XmlValues.optionalInstant(text(), name, reader);
                case "LatitudeDegrees" -> p.latitude = XmlValues.optionalDouble(text(), name, reader);
                case "LongitudeDegrees" -> p.longitude = XmlValues.optionalDouble(text(), name, reader);
                case "AltitudeMeters" -> p.altitudeMeters = XmlValues.optionalDouble(text(), name, reader);
                case "DistanceMeters" -> p.distanceMeters = XmlValues.optionalDouble(text(), name, reader);
                case "Cadence" -> p.cadenceRpm = XmlValues.optionalInteger(text(), name, reader);
                case "Speed" -> p.speedMps = XmlValues.optionalDouble(text(), name, reader);
                case "Watts" -> p.powerWatts = XmlValues.optionalInteger(text(), name, reader);
                case "Value" -> {
                    if (!"HeartRateBpm".equals(parent)) {
                        return false;
                    }
                    p.heartRateBpm = XmlValues.optionalInteger(text(), "HeartRateBpm", reader);
                }
                default -> {
                    return false;
                }
            }
            return true;
        }

        private boolean readLapLeaf(LapBuilder l, String name, @Nullable String parent) throws XMLStreamException {
            switch (name) {
                case "TotalTimeSeconds" -> l.timerTimeSeconds = XmlValues.optionalDouble(text(), name, reader);
                case "DistanceMeters" -> l.distanceMeters = XmlValues.optionalDouble(text(), name, reader);
                case "MaximumSpeed" -> l.maxSpeedMps = XmlValues.optionalDouble(text(), name, reader);
                case "Cadence" -> l.averageCadenceRpm = XmlValues.optionalInteger(text(), name, reader);
                case "AvgSpeed" -> l.averageSpeedMps = XmlValues.optionalDouble(text(), name, reader);
                case "AvgWatts" -> l.averagePowerWatts = XmlValues.optionalInteger(text(), name, reader);
                case "MaxWatts" -> l.maxPowerWatts = XmlValues.optionalInteger(text(), name, reader);
                case "Value" -> {
                    if ("AverageHeartRateBpm".equals(parent)) {
                        l.averageHeartRateBpm = XmlValues.optionalInteger(text(), parent, reader);
                    } else if ("MaximumHeartRateBpm".equals(parent)) {
                        l.maxHeartRateBpm = XmlValues.optionalInteger(text(), parent, reader);
                    } else {
                        return false;
                    }
                }
                default -> {
                    return false;
                }
            }
            return true;
        }

        private String text() throws XMLStreamException {
            return reader.getElementText();
        }

        /**
         * TCX no tiene resumen de actividad: se agregan las vueltas. Tiempo y distancia se suman solo si todas las
         * vueltas los informan; los máximos se toman de las vueltas que los tengan.
         */
        private ReportedSummary summarizeLaps() {
            if (laps.isEmpty()) {
                return new ReportedSummary(
                        sport, null, null, null, null, null, null, null, null, null, null, null, null, null);
            }
            return new ReportedSummary(
                    sport,
                    null,
                    null,
                    sumIfAll(laps.stream().map(ImportedLap::timerTimeSeconds).toList()),
                    sumIfAll(laps.stream().map(ImportedLap::distanceMeters).toList()),
                    null,
                    null,
                    null,
                    laps.stream()
                            .map(ImportedLap::maxSpeedMps)
                            .filter(java.util.Objects::nonNull)
                            .max(Double::compare)
                            .orElse(null),
                    null,
                    laps.stream()
                            .map(ImportedLap::maxHeartRateBpm)
                            .filter(java.util.Objects::nonNull)
                            .max(Integer::compare)
                            .orElse(null),
                    null,
                    null,
                    laps.stream()
                            .map(ImportedLap::maxPowerWatts)
                            .filter(java.util.Objects::nonNull)
                            .max(Integer::compare)
                            .orElse(null));
        }

        private static @Nullable Double sumIfAll(List<@Nullable Double> values) {
            double total = 0;
            for (var value : values) {
                if (value == null) {
                    return null;
                }
                total += value;
            }
            return total;
        }

        private @Nullable String sourceKey() {
            if (activityId == null) {
                return null;
            }
            return unitId == null ? "tcx:" + activityId : "tcx:" + unitId + ":" + activityId;
        }
    }

    private static @Nullable String blankToNull(String text) {
        var stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static final class LapBuilder {
        private final int lapNumber;
        private final @Nullable Instant startedAt;
        private @Nullable Double timerTimeSeconds;
        private @Nullable Double distanceMeters;
        private @Nullable Double averageSpeedMps;
        private @Nullable Double maxSpeedMps;
        private @Nullable Integer averageHeartRateBpm;
        private @Nullable Integer maxHeartRateBpm;
        private @Nullable Integer averageCadenceRpm;
        private @Nullable Integer averagePowerWatts;
        private @Nullable Integer maxPowerWatts;

        private LapBuilder(int lapNumber, @Nullable Instant startedAt) {
            this.lapNumber = lapNumber;
            this.startedAt = startedAt;
        }

        private ImportedLap build() {
            // TCX solo informa TotalTimeSeconds (cronómetro activo); el tiempo transcurrido no existe en el formato.
            return new ImportedLap(
                    lapNumber,
                    startedAt,
                    null,
                    timerTimeSeconds,
                    distanceMeters,
                    averageSpeedMps,
                    maxSpeedMps,
                    averageHeartRateBpm,
                    maxHeartRateBpm,
                    averageCadenceRpm,
                    averagePowerWatts,
                    maxPowerWatts);
        }
    }
}
