package mx.velora.api.importing.web;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import mx.velora.api.activity.SecondaryRecording;
import mx.velora.api.activity.SecondaryRecordings;
import mx.velora.api.identity.AuthenticatedUser;
import mx.velora.api.importing.job.ImportJobRepository;
import mx.velora.api.importing.job.ImportProperties;
import mx.velora.api.platform.logging.RedactedException;
import mx.velora.api.platform.storage.OriginalFileStore;
import mx.velora.api.platform.storage.OriginalFileStore.FileTooLargeException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Grabaciones secundarias (ADR-032): {@code /api/v1/secondary-recordings} y
 * {@code /api/v1/activities/{id}/secondary-recordings}. La subida y la recuperación como actividad pasan por la cola
 * de importación (asíncronas e idempotentes); su estado se consulta en {@code GET /api/v1/imports/{jobId}}.
 */
@RestController
class SecondaryRecordingController {

    private static final Logger log = LoggerFactory.getLogger(SecondaryRecordingController.class);

    static final int MAX_LIMIT = 100;

    record Accepted(UUID jobId, String status, String statusUrl) {}

    record Recording(
            UUID id,
            String source,
            UUID clientRecordingId,
            String status,
            @Nullable UUID activityId,
            @Nullable String attachedBy,
            String completeness,
            Instant startedAt,
            Instant endedAt,
            double elapsedTimeSeconds,
            @Nullable Double distanceMeters,
            int pointCount,
            @Nullable Integer averageHeartRateBpm,
            @Nullable Integer maxHeartRateBpm,
            Instant receivedAt) {

        static Recording of(SecondaryRecording r) {
            return new Recording(
                    r.id(),
                    r.source(),
                    r.clientRecordingId(),
                    r.assigned() ? "ATTACHED" : "UNASSIGNED",
                    r.activityId(),
                    r.attachedBy(),
                    r.completeness(),
                    r.startedAt(),
                    r.endedAt(),
                    r.elapsedTimeSeconds(),
                    r.distanceMeters(),
                    r.pointCount(),
                    r.averageHeartRateBpm(),
                    r.maxHeartRateBpm(),
                    r.receivedAt());
        }
    }

    record RecordingPage(List<Recording> items, long total) {}

    record Assignment(@Nullable UUID activityId) {}

    private final OriginalFileStore store;
    private final ImportJobRepository jobs;
    private final SecondaryRecordings recordings;
    private final ImportProperties properties;
    private final Clock clock;

    SecondaryRecordingController(
            OriginalFileStore store,
            ImportJobRepository jobs,
            SecondaryRecordings recordings,
            ImportProperties properties,
            Clock clock) {
        this.store = store;
        this.jobs = jobs;
        this.recordings = recordings;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Recibe el archivo y lo encola. Idempotente por {@code clientRecordingId}: el mismo archivo devuelve la grabación
     * ya procesada (200) o el trabajo pendiente (202); otro archivo con el mismo id es un conflicto (409).
     */
    @PostMapping("/api/v1/secondary-recordings")
    ResponseEntity<?> upload(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam("file") MultipartFile file,
            @RequestParam("source") String source,
            @RequestParam("clientRecordingId") UUID clientRecordingId,
            @RequestParam("completeness") String completeness)
            throws IOException {
        if (file.isEmpty()) {
            throw new ImportController.EmptyUploadException();
        }
        if (!SecondaryRecording.SOURCE_WATCH.equals(source)
                || !List.of("COMPLETE", "PARTIAL").contains(completeness)) {
            throw new InvalidRecordingRequestException();
        }
        var existing = recordings.byClientId(user.userId(), source, clientRecordingId);
        var pending = jobs.pendingSecondary(user.userId(), clientRecordingId);

        OriginalFileStore.StoredFile stored;
        try (var in = file.getInputStream()) {
            stored = store.store(user.userId(), in);
        } catch (FileTooLargeException e) {
            throw new ImportController.FileTooLargeProblem();
        }
        try {
            if (existing.isPresent()) {
                deleteQuietly(stored.storageKey());
                if (Arrays.equals(existing.get().original().sha256(), stored.sha256())) {
                    return ResponseEntity.ok(Recording.of(existing.get()));
                }
                throw new RecordingConflictException("RECORDING_CONFLICT");
            }
            if (pending.isPresent()) {
                deleteQuietly(stored.storageKey());
                if (Arrays.equals(pending.get().sha256(), stored.sha256())) {
                    return accepted(pending.get().jobId());
                }
                throw new RecordingConflictException("RECORDING_CONFLICT");
            }
            if (recordings.activityWithOriginal(user.userId(), stored.sha256()).isPresent()) {
                deleteQuietly(stored.storageKey());
                throw new RecordingConflictException("ALREADY_IMPORTED_AS_ACTIVITY");
            }
            var jobId = UUID.randomUUID();
            jobs.insertSecondary(
                    jobId,
                    user.userId(),
                    ImportController.normalizeFileName(file.getOriginalFilename()),
                    stored.storageKey(),
                    stored.sha256(),
                    stored.sizeBytes(),
                    clientRecordingId,
                    completeness,
                    properties.maxAttempts(),
                    clock.instant());
            return accepted(jobId);
        } catch (RecordingConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            deleteQuietly(stored.storageKey());
            throw e;
        }
    }

    @GetMapping("/api/v1/secondary-recordings")
    RecordingPage list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(name = "status", required = false) @Nullable String status,
            @RequestParam(name = "limit", defaultValue = "25") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        Boolean assigned;
        if (status == null) {
            assigned = null;
        } else {
            assigned = switch (status.toUpperCase(Locale.ROOT)) {
                case "ATTACHED" -> true;
                case "UNASSIGNED" -> false;
                default -> throw new InvalidRecordingRequestException();
            };
        }
        if (limit < 1 || limit > MAX_LIMIT || offset < 0) {
            throw new InvalidRecordingRequestException();
        }
        var page = recordings.list(user.userId(), assigned, offset, limit).stream()
                .map(Recording::of)
                .toList();
        return new RecordingPage(page, recordings.count(user.userId(), assigned));
    }

    @GetMapping("/api/v1/secondary-recordings/{id}")
    Recording get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return recordings.find(user.userId(), id).map(Recording::of).orElseThrow(RecordingNotFoundException::new);
    }

    @GetMapping("/api/v1/activities/{activityId}/secondary-recordings")
    List<Recording> forActivity(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID activityId) {
        return recordings.forActivity(user.userId(), activityId).stream()
                .map(Recording::of)
                .toList();
    }

    /** Adjunta a mano o desasigna ({@code activityId: null}). */
    @PutMapping("/api/v1/secondary-recordings/{id}/activity")
    Recording assign(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @RequestBody Assignment body) {
        try {
            return recordings
                    .assign(user.userId(), id, body.activityId())
                    .map(Recording::of)
                    .orElseThrow(RecordingNotFoundException::new);
        } catch (SecondaryRecordings.ActivityNotFoundException e) {
            throw new RecordingActivityNotFoundException();
        }
    }

    /** Recupera como actividad una grabación sin asignar (el ciclocomputador no grabó). */
    @PostMapping("/api/v1/secondary-recordings/{id}/promote")
    ResponseEntity<Accepted> promote(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        var recording = recordings.find(user.userId(), id).orElseThrow(RecordingNotFoundException::new);
        if (recording.assigned()) {
            throw new RecordingConflictException("RECORDING_ATTACHED");
        }
        var pending = jobs.pendingPromote(user.userId(), id);
        if (pending.isPresent()) {
            return accepted(pending.get());
        }
        var jobId = UUID.randomUUID();
        var original = recording.original();
        jobs.insertPromote(
                jobId,
                user.userId(),
                id,
                original.storageKey(),
                original.sha256(),
                original.sizeBytes(),
                properties.maxAttempts(),
                clock.instant());
        return accepted(jobId);
    }

    @DeleteMapping("/api/v1/secondary-recordings/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        if (jobs.pendingPromote(user.userId(), id).isPresent()) {
            // El trabajo de recuperación lee este mismo original.
            throw new RecordingConflictException("PROMOTION_PENDING");
        }
        var original = recordings.delete(user.userId(), id).orElseThrow(RecordingNotFoundException::new);
        afterCommit(() -> deleteQuietly(original.storageKey()));
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Accepted> accepted(UUID jobId) {
        var statusUrl = "/api/v1/imports/" + jobId;
        return ResponseEntity.accepted()
                .location(java.net.URI.create(statusUrl))
                .body(new Accepted(jobId, "QUEUED", statusUrl));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void deleteQuietly(String storageKey) {
        try {
            store.delete(storageKey);
        } catch (IOException e) {
            log.warn("No se pudo borrar un original de grabación secundaria", RedactedException.of(e));
        }
    }

    static final class InvalidRecordingRequestException extends ErrorResponseException {
        InvalidRecordingRequestException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }

    static final class RecordingNotFoundException extends ErrorResponseException {
        RecordingNotFoundException() {
            super(HttpStatus.NOT_FOUND);
        }
    }

    static final class RecordingActivityNotFoundException extends ErrorResponseException {
        RecordingActivityNotFoundException() {
            super(HttpStatus.NOT_FOUND);
        }
    }

    /**
     * Conflicto con un {@code code} para el cliente: {@code RECORDING_CONFLICT}, {@code ALREADY_IMPORTED_AS_ACTIVITY},
     * {@code RECORDING_ATTACHED} o {@code PROMOTION_PENDING}.
     */
    static final class RecordingConflictException extends ErrorResponseException {
        RecordingConflictException(String code) {
            super(HttpStatus.CONFLICT);
            getBody().setProperty("code", code);
            setDetail(
                    switch (code) {
                        case "ALREADY_IMPORTED_AS_ACTIVITY" -> "Ese archivo ya se importó como actividad.";
                        case "RECORDING_ATTACHED" ->
                            "La grabación está adjunta a una actividad; desasígnala para recuperarla.";
                        case "PROMOTION_PENDING" -> "La grabación se está recuperando como actividad.";
                        default -> "Ya existe una grabación con ese identificador y otro archivo.";
                    });
        }
    }
}
