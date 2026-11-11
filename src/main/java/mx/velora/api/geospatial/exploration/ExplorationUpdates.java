package mx.velora.api.geospatial.exploration;

import java.util.HashSet;
import java.util.UUID;
import mx.velora.api.activity.ActivityImported;
import mx.velora.api.activity.ActivityReprocessed;
import mx.velora.api.identity.TrackPrivacyChanged;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Mantiene al día las celdas de exploración (ADR-018): al importar o reprocesar una actividad, al cambiar la
 * privacidad y al arrancar (actividades previas a esta función o calculadas con otra versión del algoritmo). Los
 * eventos quedan en el registro persistente de Modulith: si la API se reinicia a mitad, se reintentan.
 */
@Component
class ExplorationUpdates {

    private static final Logger log = LoggerFactory.getLogger(ExplorationUpdates.class);
    private static final int BATCH = 100;

    private final ExplorationCells cells;
    private final boolean catchUpOnStartup;

    ExplorationUpdates(
            ExplorationCells cells, @Value("${velora.exploration.catch-up-on-startup:true}") boolean catchUpOnStartup) {
        this.cells = cells;
        this.catchUpOnStartup = catchUpOnStartup;
    }

    @ApplicationModuleListener
    void on(ActivityImported event) {
        cells.compute(event.activityId());
    }

    @ApplicationModuleListener
    void on(ActivityReprocessed event) {
        cells.compute(event.activityId());
    }

    @ApplicationModuleListener
    void on(TrackPrivacyChanged event) {
        catchUp(event.userId());
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        if (catchUpOnStartup) {
            catchUp(null);
        }
    }

    /** Recalcula por lotes lo pendiente; una actividad que falla no se reintenta en esta pasada. */
    void catchUp(@Nullable UUID userId) {
        var attempted = new HashSet<UUID>();
        var done = 0;
        while (true) {
            var batch = cells.outdated(userId, BATCH).stream()
                    .filter(id -> !attempted.contains(id))
                    .toList();
            if (batch.isEmpty()) {
                break;
            }
            for (var id : batch) {
                attempted.add(id);
                try {
                    cells.compute(id);
                    done++;
                } catch (RuntimeException e) {
                    log.warn("No se pudieron calcular las celdas de exploración de la actividad {}", id, e);
                }
            }
        }
        if (done > 0) {
            log.info("Celdas de exploración al día: {} actividades recalculadas", done);
        }
    }
}
