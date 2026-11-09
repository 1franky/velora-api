package mx.velora.api.importing.parsing;

import com.garmin.fit.DateTime;
import com.garmin.fit.Decode;
import com.garmin.fit.Event;
import com.garmin.fit.EventMesg;
import com.garmin.fit.EventMesgListener;
import com.garmin.fit.EventType;
import com.garmin.fit.File;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.FileIdMesgListener;
import com.garmin.fit.FitRuntimeException;
import com.garmin.fit.LapMesg;
import com.garmin.fit.LapMesgListener;
import com.garmin.fit.MesgBroadcaster;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.RecordMesgListener;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mx.velora.api.importing.FileFormat;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.ImportedActivity;
import mx.velora.api.importing.ImportedLap;
import mx.velora.api.importing.ParserInfo;
import mx.velora.api.importing.RejectionReason;
import org.jspecify.annotations.Nullable;

/**
 * Adaptador del Garmin FIT SDK (ADR-009). Es la única clase que conoce el SDK, para poder sustituirlo.
 *
 * <p>Lee archivos FIT de tipo actividad: {@code file_id} (clave de origen), {@code record} (muestras), {@code lap}
 * (vueltas) y los eventos de cronómetro, que delimitan segmentos: cada reanudación tras una pausa abre un segmento
 * nuevo. Prefiere los campos {@code enhanced_*} de velocidad y altitud cuando existen. Los valores inválidos del
 * protocolo llegan como {@code null} desde el SDK y así se conservan.
 */
public final class FitParser implements ActivityFileParser {

    static final ParserInfo INFO = new ParserInfo("velora-fit", "1+garmin-fit-sdk-21.221.0");

    /** Conversión de semicírculos FIT a grados: 180 / 2^31. */
    private static final double DEGREES_PER_SEMICIRCLE = 180.0 / (1L << 31);

    private final ParserLimits limits;

    public FitParser(ParserLimits limits) {
        this.limits = limits;
    }

    @Override
    public FileFormat format() {
        return FileFormat.FIT;
    }

    @Override
    public ImportedActivity parse(InputStream content) {
        var session = new Session(limits);
        var decode = new Decode();
        var broadcaster = new MesgBroadcaster(decode);
        broadcaster.addListener((FileIdMesgListener) session::onFileId);
        broadcaster.addListener((EventMesgListener) session::onEvent);
        broadcaster.addListener((RecordMesgListener) session::onRecord);
        broadcaster.addListener((LapMesgListener) session::onLap);
        boolean completed;
        try {
            completed = decode.read(content, broadcaster, broadcaster);
        } catch (FitRuntimeException e) {
            // El mensaje del SDK puede describir el contenido; solo se informa que el archivo está dañado.
            throw malformed();
        }
        if (!completed) {
            throw malformed();
        }
        return session.result();
    }

    private static ImportRejectedException malformed() {
        return new ImportRejectedException(RejectionReason.MALFORMED_FILE, "archivo FIT inválido, incompleto o dañado");
    }

    private static @Nullable Instant instant(@Nullable DateTime value) {
        return value == null ? null : value.getInstant();
    }

    private static @Nullable Double degrees(@Nullable Integer semicircles) {
        return semicircles == null ? null : semicircles * DEGREES_PER_SEMICIRCLE;
    }

    private static @Nullable Double toDouble(@Nullable Number value) {
        return value == null ? null : value.doubleValue();
    }

    private static @Nullable Integer toInteger(@Nullable Number value) {
        return value == null ? null : value.intValue();
    }

    private static @Nullable Double preferEnhanced(@Nullable Float enhanced, @Nullable Float standard) {
        return toDouble(enhanced != null ? enhanced : standard);
    }

    /** Estado de un parseo; una instancia por archivo. */
    private static final class Session {
        private final PointCollector points;
        private final List<ImportedLap> laps = new ArrayList<>();
        private boolean fileIdSeen;
        private @Nullable String sourceKey;
        private int segment;
        private boolean timerStopped;

        private Session(ParserLimits limits) {
            this.points = new PointCollector(limits);
        }

        private void onFileId(FileIdMesg mesg) {
            if (fileIdSeen) {
                return; // Solo cuenta el primer file_id (archivos FIT encadenados no se admiten).
            }
            fileIdSeen = true;
            if (mesg.getType() != File.ACTIVITY) {
                throw new ImportRejectedException(
                        RejectionReason.UNSUPPORTED_FORMAT, "el archivo FIT no es una actividad registrada");
            }
            var timeCreated = mesg.getTimeCreated();
            if (timeCreated != null) {
                sourceKey = "fit:" + orEmpty(mesg.getManufacturer()) + ":" + orEmpty(mesg.getSerialNumber()) + ":"
                        + timeCreated.getTimestamp();
            }
        }

        private void onEvent(EventMesg mesg) {
            if (mesg.getEvent() != Event.TIMER || mesg.getEventType() == null) {
                return;
            }
            if (mesg.getEventType() == EventType.START) {
                if (timerStopped && points.size() > 0) {
                    segment++;
                }
                timerStopped = false;
            } else if (isStop(mesg.getEventType())) {
                timerStopped = true;
            }
        }

        private void onRecord(RecordMesg mesg) {
            requireFileId();
            var point = new TrackPointBuilder(segment);
            point.time = instant(mesg.getTimestamp());
            point.latitude = degrees(mesg.getPositionLat());
            point.longitude = degrees(mesg.getPositionLong());
            if (point.latitude == null || point.longitude == null) {
                point.latitude = null;
                point.longitude = null;
            }
            point.altitudeMeters = preferEnhanced(mesg.getEnhancedAltitude(), mesg.getAltitude());
            point.distanceMeters = toDouble(mesg.getDistance());
            point.speedMps = preferEnhanced(mesg.getEnhancedSpeed(), mesg.getSpeed());
            point.heartRateBpm = toInteger(mesg.getHeartRate());
            point.cadenceRpm = toInteger(mesg.getCadence());
            point.powerWatts = toInteger(mesg.getPower());
            point.temperatureCelsius = toDouble(mesg.getTemperature());
            points.add(point.build());
        }

        private void onLap(LapMesg mesg) {
            requireFileId();
            laps.add(new ImportedLap(
                    laps.size() + 1,
                    instant(mesg.getStartTime()),
                    toDouble(mesg.getTotalElapsedTime()),
                    toDouble(mesg.getTotalTimerTime()),
                    toDouble(mesg.getTotalDistance()),
                    preferEnhanced(mesg.getEnhancedAvgSpeed(), mesg.getAvgSpeed()),
                    preferEnhanced(mesg.getEnhancedMaxSpeed(), mesg.getMaxSpeed()),
                    toInteger(mesg.getAvgHeartRate()),
                    toInteger(mesg.getMaxHeartRate()),
                    toInteger(mesg.getAvgCadence()),
                    toInteger(mesg.getAvgPower()),
                    toInteger(mesg.getMaxPower())));
        }

        private void requireFileId() {
            if (!fileIdSeen) {
                throw new ImportRejectedException(
                        RejectionReason.MALFORMED_FILE, "el archivo FIT no comienza con file_id");
            }
        }

        private ImportedActivity result() {
            if (!fileIdSeen) {
                throw new ImportRejectedException(RejectionReason.MALFORMED_FILE, "el archivo FIT no tiene file_id");
            }
            var startedAt = points.firstTime();
            if (startedAt == null && !laps.isEmpty()) {
                startedAt = laps.getFirst().startedAt();
            }
            return new ImportedActivity(FileFormat.FIT, INFO, sourceKey, null, startedAt, laps, points.points());
        }

        private static boolean isStop(EventType type) {
            return type == EventType.STOP
                    || type == EventType.STOP_ALL
                    || type == EventType.STOP_DISABLE
                    || type == EventType.STOP_DISABLE_ALL;
        }

        private static String orEmpty(@Nullable Object value) {
            return value == null ? "" : value.toString();
        }
    }
}
