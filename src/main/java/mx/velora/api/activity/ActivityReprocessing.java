package mx.velora.api.activity;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import mx.velora.api.activity.persistence.ActivityWriter;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reemplaza los datos derivados de una actividad con los recalculados desde su original
 * ({@code 05-import-pipeline.md} §7). Idempotente: no crea actividades, no toca el original y guarda los valores
 * anteriores en {@code activity_metrics_history}. Conserva título, bicicleta, dispositivo y zona horaria de
 * importación.
 */
@Service
public class ActivityReprocessing {

    private final ActivityWriter writer;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    ActivityReprocessing(ActivityWriter writer, Clock clock, ApplicationEventPublisher events) {
        this.writer = writer;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Original de una actividad del usuario.
     *
     * @param storageKey clave en el almacén de originales
     * @param sha256 hash del archivo
     * @param sizeBytes tamaño
     * @param bikeId bicicleta asociada, para inferir el subtipo al recalcular
     */
    public record Original(
            String storageKey,
            byte[] sha256,
            long sizeBytes,
            @Nullable UUID bikeId) {}

    public Optional<Original> originalOf(UUID userId, UUID activityId) {
        return writer.originalOf(userId, activityId)
                .map(ref -> new Original(ref.storageKey(), ref.sha256(), ref.sizeBytes(), ref.bikeId()));
    }

    /**
     * @param recalculated datos recalculados; se ignoran título, bici, dispositivo, zona horaria y archivo (salvo la
     *     versión del parser)
     * @return {@code false} si la actividad ya no existe
     */
    @Transactional
    public boolean replace(UUID userId, UUID activityId, NewActivity recalculated) {
        var original = writer.originalOf(userId, activityId);
        var zone = writer.lockForReprocess(activityId);
        if (original.isEmpty() || zone.isEmpty()) {
            return false;
        }
        var effective = ActivityWriter.effective(recalculated.metrics());
        if (!effective.values().containsKey(Metric.ELAPSED_TIME)
                || !effective.values().containsKey(Metric.MOVING_TIME)) {
            throw new IllegalArgumentException("Faltan tiempo transcurrido o tiempo en movimiento");
        }
        var now = clock.instant();
        var localDate = recalculated.startedAt().atZone(ZoneId.of(zone.get())).toLocalDate();
        writer.archiveAndClearDerived(activityId, original.get().parserVersion(), now);
        writer.updateRecalculated(activityId, recalculated, localDate, effective, now);
        writer.insertMetrics(activityId, recalculated.metrics());
        writer.insertTrack(activityId, recalculated.samples());
        writer.insertLaps(activityId, recalculated.laps());
        writer.insertStreams(activityId, recalculated.samples());
        events.publishEvent(new ActivityReprocessed(activityId, userId));
        return true;
    }
}
