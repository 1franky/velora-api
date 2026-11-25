package mx.velora.api.activity;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import mx.velora.api.activity.persistence.ActivityWriter;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caso de uso para registrar una actividad validada (ADR-012). Lo invoca {@code importing}; todo ocurre en una sola
 * transacción: actividad, métricas, track, vueltas, streams y referencia al original.
 */
@Service
public class ActivityRegistration {

    /** Formato visible es-MX ({@code AGENTS.md}). */
    static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    static final int MAX_TITLE_LENGTH = 200;

    private final ActivityWriter writer;
    private final SecondaryRecordings secondaryRecordings;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    ActivityRegistration(
            ActivityWriter writer,
            SecondaryRecordings secondaryRecordings,
            Clock clock,
            ApplicationEventPublisher events) {
        this.writer = writer;
        this.secondaryRecordings = secondaryRecordings;
        this.clock = clock;
        this.events = events;
    }

    /**
     * @return identificador de la actividad creada
     * @throws DuplicateOriginalException si el usuario ya importó un archivo idéntico (mismo SHA-256)
     * @throws IllegalArgumentException si faltan el tiempo transcurrido o en movimiento
     */
    @Transactional
    public UUID register(NewActivity cmd) {
        return register(cmd, null);
    }

    /**
     * Como {@link #register(NewActivity)}, y además quita la grabación secundaria {@code promotedRecordingId} en la
     * misma transacción: se recuperó como esta actividad (ADR-032) y no deben quedar dos copias lógicas.
     */
    @Transactional
    public UUID register(NewActivity cmd, @Nullable UUID promotedRecordingId) {
        var existing = writer.findActivityWithOriginal(cmd.userId(), cmd.file().sha256());
        if (existing.isPresent()) {
            throw new DuplicateOriginalException(existing.get());
        }
        var effective = ActivityWriter.effective(cmd.metrics());
        if (!effective.values().containsKey(Metric.ELAPSED_TIME)
                || !effective.values().containsKey(Metric.MOVING_TIME)) {
            throw new IllegalArgumentException("Faltan tiempo transcurrido o tiempo en movimiento");
        }
        var id = UUID.randomUUID();
        var now = clock.instant();
        var localDate = cmd.startedAt().atZone(cmd.timeZone()).toLocalDate();
        var title = title(cmd.title(), localDate);
        writer.insertActivity(id, cmd, title, localDate, effective, now);
        writer.insertMetrics(id, cmd.metrics());
        writer.insertTrack(id, cmd.samples());
        writer.insertLaps(id, cmd.laps());
        writer.insertStreams(id, cmd.samples());
        writer.insertFile(id, cmd, now);
        if (promotedRecordingId != null) {
            secondaryRecordings.consume(cmd.userId(), promotedRecordingId);
        }
        var elapsed = effective.values().get(Metric.ELAPSED_TIME);
        secondaryRecordings.attachPendingTo(
                id, cmd.userId(), cmd.startedAt(), cmd.startedAt().plusMillis(Math.round(elapsed * 1000)));
        events.publishEvent(new ActivityImported(id, cmd.userId(), cmd.startedAt()));
        return id;
    }

    /** Título del archivo recortado, o "Rodada del dd/MM/yyyy" con la fecha deportiva local (ADR-012). */
    static String title(@Nullable String fromFile, java.time.LocalDate localDate) {
        if (fromFile != null && !fromFile.isBlank()) {
            var stripped = fromFile.strip();
            return stripped.length() <= MAX_TITLE_LENGTH ? stripped : stripped.substring(0, MAX_TITLE_LENGTH);
        }
        return "Rodada del " + TITLE_DATE.format(localDate);
    }

    /** El mismo archivo ya está importado; incluye la actividad existente. */
    public static final class DuplicateOriginalException extends RuntimeException {
        private final UUID existingActivityId;

        public DuplicateOriginalException(UUID existingActivityId) {
            super("El archivo ya fue importado en la actividad " + existingActivityId);
            this.existingActivityId = existingActivityId;
        }

        public UUID existingActivityId() {
            return existingActivityId;
        }
    }
}
