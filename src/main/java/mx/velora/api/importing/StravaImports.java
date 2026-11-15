package mx.velora.api.importing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.UUID;
import mx.velora.api.importing.job.ImportJobRepository;
import mx.velora.api.importing.job.ImportProperties;
import mx.velora.api.platform.storage.OriginalFileStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entrada de la sincronización con Strava al pipeline (ADR-030): guarda el documento como original y encola un
 * trabajo de origen {@code STRAVA}. Desde ahí sigue el mismo camino que un archivo subido: validación, métricas,
 * deduplicación, registro, reintentos y reproceso.
 */
@Service
public class StravaImports {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OriginalFileStore store;
    private final ImportJobRepository jobs;
    private final ImportProperties properties;
    private final Clock clock;

    StravaImports(OriginalFileStore store, ImportJobRepository jobs, ImportProperties properties, Clock clock) {
        this.store = store;
        this.jobs = jobs;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return el id del trabajo de importación */
    public UUID submit(UUID userId, StravaActivityDocument document) {
        var bytes = JSON.writeValueAsBytes(document);
        OriginalFileStore.StoredFile stored;
        try {
            stored = store.store(userId, new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var jobId = UUID.randomUUID();
        try {
            jobs.insertStrava(
                    jobId,
                    userId,
                    "strava-" + document.activity().id() + ".json",
                    stored.storageKey(),
                    stored.sha256(),
                    stored.sizeBytes(),
                    properties.maxAttempts(),
                    clock.instant());
        } catch (RuntimeException e) {
            try {
                store.delete(stored.storageKey());
            } catch (IOException | RuntimeException ignored) {
                // El original queda huérfano y no es accesible por la API.
            }
            throw e;
        }
        return jobId;
    }
}
