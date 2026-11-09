package mx.velora.api.activity;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import mx.velora.api.activity.persistence.ActivityWriter;
import org.jspecify.annotations.Nullable;
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
    private final Clock clock;

    ActivityRegistration(ActivityWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    /**
     * @return identificador de la actividad creada
     * @throws DuplicateOriginalException si el usuario ya importó un archivo idéntico (mismo SHA-256)
     * @throws IllegalArgumentException si faltan el tiempo transcurrido o en movimiento
     */
    @Transactional
    public UUID register(NewActivity cmd) {
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
