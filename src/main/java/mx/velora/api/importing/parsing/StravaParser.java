package mx.velora.api.importing.parsing;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import mx.velora.api.importing.FileFormat;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.ImportedActivity;
import mx.velora.api.importing.ParserInfo;
import mx.velora.api.importing.RejectionReason;
import mx.velora.api.importing.ReportedSummary;
import mx.velora.api.importing.StravaActivityDocument;
import mx.velora.api.importing.TrackPoint;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lee un {@link StravaActivityDocument} (ADR-030). Cada índice de {@code time} es una muestra; los demás streams se
 * alinean por índice. El resumen de Strava hace las veces de los totales del dispositivo, y su {@code sport_type} se
 * traduce al subdeporte FIT equivalente para que {@code ActivitySubType} lo entienda.
 */
public final class StravaParser implements ActivityFileParser {

    static final ParserInfo INFO = new ParserInfo("velora-strava", "1");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ParserLimits limits;

    public StravaParser(ParserLimits limits) {
        this.limits = limits;
    }

    @Override
    public FileFormat format() {
        return FileFormat.STRAVA;
    }

    @Override
    public ImportedActivity parse(InputStream content) {
        StravaActivityDocument document;
        try {
            document = JSON.readValue(content, StravaActivityDocument.class);
        } catch (JacksonException | NullPointerException e) {
            throw new ImportRejectedException(RejectionReason.MALFORMED_FILE, "documento de Strava inválido");
        }
        if (!StravaActivityDocument.FORMAT.equals(document.format())) {
            throw new ImportRejectedException(
                    RejectionReason.UNSUPPORTED_FORMAT, "versión de documento de Strava desconocida");
        }
        var summary = document.activity();
        var streams = document.streams();
        var time = streams.time();
        if (time == null || time.isEmpty()) {
            throw new ImportRejectedException(RejectionReason.INVALID_DATA, "la actividad de Strava no tiene muestras");
        }
        var size = time.size();
        requireAligned(streams.latlng(), size);
        requireAligned(streams.altitude(), size);
        requireAligned(streams.distance(), size);
        requireAligned(streams.heartrate(), size);
        requireAligned(streams.cadence(), size);
        requireAligned(streams.watts(), size);
        requireAligned(streams.velocitySmooth(), size);
        requireAligned(streams.temp(), size);

        var points = new PointCollector(limits);
        for (int i = 0; i < size; i++) {
            var offset = time.get(i);
            var position = streams.latlng() == null ? null : streams.latlng().get(i);
            if (position != null && position.length != 2) {
                throw new ImportRejectedException(RejectionReason.MALFORMED_FILE, "posición de Strava inválida");
            }
            points.add(new TrackPoint(
                    0,
                    offset == null ? null : summary.startDate().plusSeconds(offset),
                    position == null ? null : position[0],
                    position == null ? null : position[1],
                    at(streams.altitude(), i),
                    at(streams.distance(), i),
                    integer(at(streams.heartrate(), i)),
                    integer(at(streams.cadence(), i)),
                    integer(at(streams.watts(), i)),
                    at(streams.velocitySmooth(), i),
                    at(streams.temp(), i)));
        }
        Instant startedAt = points.firstTime() != null ? points.firstTime() : summary.startDate();
        return new ImportedActivity(
                FileFormat.STRAVA,
                INFO,
                "strava:" + summary.id(),
                summary.name(),
                startedAt,
                List.of(),
                points.points(),
                new ReportedSummary(
                        "CYCLING",
                        subSport(summary.sportType(), summary.trainer()),
                        summary.elapsedTime(),
                        summary.movingTime(),
                        summary.distance(),
                        summary.totalElevationGain(),
                        null,
                        summary.averageSpeed(),
                        summary.maxSpeed(),
                        integer(summary.averageHeartrate()),
                        integer(summary.maxHeartrate()),
                        integer(summary.averageCadence()),
                        integer(summary.averageWatts()),
                        integer(summary.maxWatts())));
    }

    /** {@code sport_type} de Strava → nombre de subdeporte FIT que reconoce {@code ActivitySubType}. */
    static @Nullable String subSport(@Nullable String sportType, boolean trainer) {
        if (trainer) {
            return "INDOOR_CYCLING";
        }
        if (sportType == null) {
            return null;
        }
        return switch (sportType.toUpperCase(Locale.ROOT)) {
            case "RIDE" -> "ROAD";
            case "MOUNTAINBIKERIDE", "EMOUNTAINBIKERIDE" -> "MOUNTAIN";
            case "GRAVELRIDE" -> "GRAVEL_CYCLING";
            case "VIRTUALRIDE" -> "VIRTUAL_ACTIVITY";
            default -> null;
        };
    }

    private static void requireAligned(@Nullable List<?> stream, int size) {
        if (stream != null && stream.size() != size) {
            throw new ImportRejectedException(
                    RejectionReason.MALFORMED_FILE, "los streams de Strava no tienen la misma longitud");
        }
    }

    private static @Nullable Double at(@Nullable List<@Nullable Double> stream, int index) {
        if (stream == null) {
            return null;
        }
        var value = stream.get(index);
        return value == null || !Double.isFinite(value) ? null : value;
    }

    private static @Nullable Integer integer(@Nullable Double value) {
        return value == null || !Double.isFinite(value) ? null : (int) Math.round(value);
    }
}
