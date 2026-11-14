package mx.velora.api.importing.job;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import mx.velora.api.activity.ActivityLookup;
import mx.velora.api.activity.ActivityRegistration;
import mx.velora.api.activity.ActivityRegistration.DuplicateOriginalException;
import mx.velora.api.activity.ActivityReprocessing;
import mx.velora.api.activity.NewActivity;
import mx.velora.api.catalog.CatalogLookup;
import mx.velora.api.identity.UserTimeZones;
import mx.velora.api.importing.ActivityFileReader;
import mx.velora.api.importing.DerivedMetrics;
import mx.velora.api.importing.ImportRejectedException;
import mx.velora.api.importing.ImportedActivity;
import mx.velora.api.importing.ValidatedActivity;
import mx.velora.api.importing.ValidationWarning;
import mx.velora.api.importing.derive.DerivedMetricsCalculator;
import mx.velora.api.importing.job.ImportJobRepository.ClaimedJob;
import mx.velora.api.importing.parsing.ParserLimits;
import mx.velora.api.importing.validation.ActivityValidator;
import mx.velora.api.platform.logging.RedactedException;
import mx.velora.api.platform.storage.OriginalFileStore;
import mx.velora.api.platform.storage.OriginalFileStore.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Procesa un trabajo tomado de la cola: leer y verificar el original, parsear, validar, derivar, deduplicar y
 * registrar. Es idempotente: si un intento anterior registró la actividad y cayó antes de marcar el trabajo, el
 * reintento lo reconoce y lo completa.
 *
 * <p>Deduplicación (ADR-009): mismo archivo o misma clave FIT → {@code DUPLICATE}; clave TCX o heurística → solo
 * advertencia {@code POSSIBLE_DUPLICATE}.
 */
@Component
class ImportProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImportProcessor.class);

    static final String POSSIBLE_DUPLICATE = "POSSIBLE_DUPLICATE";

    private final ImportJobRepository jobs;
    private final OriginalFileStore store;
    private final ActivityRegistration registration;
    private final ActivityReprocessing reprocessing;
    private final ActivityLookup activities;
    private final PossibleDuplicates duplicates;
    private final CatalogLookup catalog;
    private final UserTimeZones timeZones;
    private final ImportProperties properties;
    private final Clock clock;
    private final ActivityFileReader reader;
    private final ActivityValidator validator;

    ImportProcessor(
            ImportJobRepository jobs,
            OriginalFileStore store,
            ActivityRegistration registration,
            ActivityReprocessing reprocessing,
            ActivityLookup activities,
            PossibleDuplicates duplicates,
            CatalogLookup catalog,
            UserTimeZones timeZones,
            ImportProperties properties,
            Clock clock) {
        this.jobs = jobs;
        this.store = store;
        this.registration = registration;
        this.reprocessing = reprocessing;
        this.activities = activities;
        this.duplicates = duplicates;
        this.catalog = catalog;
        this.timeZones = timeZones;
        this.properties = properties;
        this.clock = clock;
        this.reader = ActivityFileReader.withDefaultParsers(new ParserLimits(properties.maxPoints()));
        this.validator = new ActivityValidator(clock);
    }

    /** Cómo terminó un intento; alimenta las métricas de importación. */
    enum Outcome {
        COMPLETED,
        DUPLICATE,
        REJECTED,
        RETRY,
        FAILED
    }

    Outcome process(ClaimedJob job) {
        if (job.isReprocess() && job.activityId() == null) {
            // La actividad se borró mientras el reproceso esperaba (FK con ON DELETE SET NULL).
            jobs.fail(job.id(), "ACTIVITY_NOT_FOUND", ImportMessages.ACTIVITY_GONE, clock.instant());
            return Outcome.FAILED;
        }
        String format = null;
        try {
            var imported = read(job);
            format = imported.format().name();
            var validated = validator.validate(imported);
            var derived = DerivedMetricsCalculator.compute(validated.activity(), DerivedMetrics.Parameters.DEFAULT);
            return job.isReprocess()
                    ? reprocess(job, validated, derived, format)
                    : handle(job, validated, derived, format);
        } catch (ImportRejectedException e) {
            jobs.reject(job.id(), e.reason().name(), ImportMessages.detail(e), clock.instant());
            // Un reproceso rechazado deja la actividad y su original intactos.
            if (!job.isReprocess()) {
                deleteOriginal(job);
            }
            return Outcome.REJECTED;
        } catch (RuntimeException | IOException e) {
            return onUnexpected(job, e);
        }
    }

    private ImportedActivity read(ClaimedJob job) throws IOException {
        try (var in = store.open(new StoredFile(job.storageKey(), job.sha256(), job.sizeBytes()))) {
            // El detector de formato usa SequenceInputStream, que cierra cada stream al agotarlo; el cierre real
            // lo hace este try.
            var imported = reader.read(new java.io.FilterInputStream(in) {
                @Override
                public void close() {}
            });
            // El SHA-256 se verifica al llegar al final: leer lo que el parser no consumió.
            in.transferTo(OutputStream.nullOutputStream());
            return imported;
        }
    }

    private Outcome handle(ClaimedJob job, ValidatedActivity validated, DerivedMetrics derived, String format) {
        var activity = validated.activity();
        var possibleDuplicates = new LinkedHashSet<UUID>();
        var sourceKey = activity.sourceActivityKey();
        if (sourceKey != null) {
            var sameKey = activities.withSourceKey(job.userId(), sourceKey);
            if (sourceKey.startsWith("fit:") && !sameKey.isEmpty()) {
                // file_id de FIT identifica la actividad del dispositivo: es la misma aunque el archivo cambie.
                return finishAsDuplicateOrOwn(job, sameKey.getFirst(), format, validated, possibleDuplicates);
            }
            possibleDuplicates.addAll(sameKey);
        }
        possibleDuplicates.addAll(duplicates.near(job.userId(), validated.footprint()));

        try {
            var id = registration.register(ActivityMapper.toNewActivity(validated, derived, context(job, activity)));
            possibleDuplicates.remove(id);
            jobs.complete(
                    job.id(),
                    id,
                    format,
                    warnings(validated, possibleDuplicates),
                    List.copyOf(possibleDuplicates),
                    clock.instant());
            return Outcome.COMPLETED;
        } catch (DuplicateOriginalException e) {
            return finishAsDuplicateOrOwn(job, e.existingActivityId(), format, validated, possibleDuplicates);
        }
    }

    /** Recalcula una actividad existente desde su original, sin deduplicar ni crear actividades. */
    private Outcome reprocess(ClaimedJob job, ValidatedActivity validated, DerivedMetrics derived, String format) {
        var now = clock.instant();
        var target = job.activityId();
        if (target == null
                || !reprocessing.replace(
                        job.userId(),
                        target,
                        ActivityMapper.toNewActivity(validated, derived, context(job, validated.activity())))) {
            jobs.fail(job.id(), "ACTIVITY_NOT_FOUND", ImportMessages.ACTIVITY_GONE, now);
            return Outcome.FAILED;
        }
        jobs.complete(job.id(), target, format, warnings(validated, List.of()), List.of(), now);
        return Outcome.COMPLETED;
    }

    /** Si la actividad existente usa este mismo original, la registró un intento anterior: se completa. */
    private Outcome finishAsDuplicateOrOwn(
            ClaimedJob job,
            UUID existing,
            String format,
            ValidatedActivity validated,
            java.util.Set<UUID> possibleDuplicates) {
        if (activities.usesOriginal(existing, job.storageKey())) {
            possibleDuplicates.remove(existing);
            jobs.complete(
                    job.id(),
                    existing,
                    format,
                    warnings(validated, possibleDuplicates),
                    List.copyOf(possibleDuplicates),
                    clock.instant());
            return Outcome.COMPLETED;
        }
        jobs.duplicate(job.id(), existing, format, clock.instant());
        deleteOriginal(job);
        return Outcome.DUPLICATE;
    }

    private ActivityMapper.Context context(ClaimedJob job, ImportedActivity activity) {
        var bikeType = job.bikeId() == null
                ? null
                : catalog.bikeType(job.userId(), job.bikeId()).orElse(null);
        return new ActivityMapper.Context(
                job.userId(),
                job.bikeId(),
                job.deviceId(),
                bikeType,
                job.activityTitle(),
                timeZones.timeZoneOf(job.userId()),
                new NewActivity.OriginalFile(
                        job.storageKey(),
                        job.sha256(),
                        job.sizeBytes(),
                        activity.parser().name(),
                        activity.parser().version()));
    }

    private static String warnings(ValidatedActivity validated, java.util.Collection<UUID> possibleDuplicates) {
        var items = new ArrayList<String>();
        for (ValidationWarning w : validated.warnings()) {
            items.add("{\"code\":\"" + w.code().name() + "\",\"affectedCount\":" + w.affectedCount() + "}");
        }
        if (!possibleDuplicates.isEmpty()) {
            items.add("{\"code\":\"" + POSSIBLE_DUPLICATE + "\",\"affectedCount\":" + possibleDuplicates.size() + "}");
        }
        return items.stream().collect(Collectors.joining(",", "[", "]"));
    }

    private Outcome onUnexpected(ClaimedJob job, Exception e) {
        var now = clock.instant();
        if (job.attemptCount() < job.maxAttempts()) {
            var delay = properties.retryBaseDelay().multipliedBy(1L << Math.min(job.attemptCount() - 1, 10));
            log.warn(
                    "Importación {} falló en el intento {} de {}; se reintentará en {}",
                    job.id(),
                    job.attemptCount(),
                    job.maxAttempts(),
                    delay,
                    RedactedException.of(e));
            jobs.retryLater(job.id(), "INTERNAL_ERROR", ImportMessages.INTERNAL_ERROR, now.plus(delay), now);
            return Outcome.RETRY;
        }
        log.error(
                "Importación {} falló definitivamente tras {} intentos",
                job.id(),
                job.attemptCount(),
                RedactedException.of(e));
        // El original se conserva para diagnóstico o reintento manual.
        jobs.fail(job.id(), "INTERNAL_ERROR", ImportMessages.INTERNAL_ERROR, now);
        return Outcome.FAILED;
    }

    private void deleteOriginal(ClaimedJob job) {
        try {
            store.delete(job.storageKey());
        } catch (IOException e) {
            log.warn("No se pudo borrar el original del trabajo {}", job.id(), RedactedException.of(e));
        }
    }
}
