package mx.velora.api.importing.parsing;

import com.garmin.fit.BufferEncoder;
import com.garmin.fit.DateTime;
import com.garmin.fit.Event;
import com.garmin.fit.EventMesg;
import com.garmin.fit.EventType;
import com.garmin.fit.File;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.Fit;
import com.garmin.fit.LapMesg;
import com.garmin.fit.Mesg;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.SessionMesg;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Genera archivos FIT sintéticos en memoria con el codificador del Garmin FIT SDK. No se versionan binarios ni datos
 * reales (ADR-009).
 */
public final class FitFixtures {

    public static final Instant START = Instant.parse("2026-11-08T13:15:00Z");
    public static final int MANUFACTURER_DEVELOPMENT = 255;
    public static final long SERIAL_NUMBER = 3_900_000_001L;

    private FitFixtures() {}

    public static Builder activity() {
        return new Builder(File.ACTIVITY);
    }

    public static Builder ofType(File type) {
        return new Builder(type);
    }

    public static DateTime at(int offsetSeconds) {
        return new DateTime(START.plusSeconds(offsetSeconds));
    }

    /** Grados a semicírculos FIT. */
    public static int semicircles(double degrees) {
        return (int) Math.round(degrees * ((1L << 31) / 180.0));
    }

    public static final class Builder {
        private final List<Mesg> mesgs = new ArrayList<>();

        private Builder(File type) {
            var fileId = new FileIdMesg();
            fileId.setType(type);
            fileId.setManufacturer(MANUFACTURER_DEVELOPMENT);
            fileId.setProduct(1);
            fileId.setSerialNumber(SERIAL_NUMBER);
            fileId.setTimeCreated(at(0));
            mesgs.add(fileId);
        }

        public Builder timerStart(int offsetSeconds) {
            return timer(offsetSeconds, EventType.START);
        }

        public Builder timerStop(int offsetSeconds) {
            return timer(offsetSeconds, EventType.STOP_ALL);
        }

        /** Registro con marca de tiempo; {@code fields} completa el resto de campos. */
        public Builder record(int offsetSeconds, Consumer<RecordMesg> fields) {
            var record = new RecordMesg();
            record.setTimestamp(at(offsetSeconds));
            fields.accept(record);
            mesgs.add(record);
            return this;
        }

        public Builder record(int offsetSeconds) {
            return record(offsetSeconds, record -> {});
        }

        public Builder lap(Consumer<LapMesg> fields) {
            var lap = new LapMesg();
            fields.accept(lap);
            mesgs.add(lap);
            return this;
        }

        public Builder session(Consumer<SessionMesg> fields) {
            var session = new SessionMesg();
            fields.accept(session);
            mesgs.add(session);
            return this;
        }

        public byte[] build() {
            var encoder = new BufferEncoder(Fit.ProtocolVersion.V2_0);
            encoder.write(mesgs);
            return encoder.close();
        }

        private Builder timer(int offsetSeconds, EventType type) {
            var event = new EventMesg();
            event.setTimestamp(at(offsetSeconds));
            event.setEvent(Event.TIMER);
            event.setEventType(type);
            mesgs.add(event);
            return this;
        }
    }
}
