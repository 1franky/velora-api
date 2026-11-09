package mx.velora.api.importing.web;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import mx.velora.api.catalog.CatalogLookup;
import mx.velora.api.identity.AuthenticatedUser;
import mx.velora.api.importing.job.ImportJobRepository;
import mx.velora.api.importing.job.ImportProperties;
import mx.velora.api.platform.storage.OriginalFileStore;
import mx.velora.api.platform.storage.OriginalFileStore.FileTooLargeException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** {@code POST /api/v1/activities/imports} y {@code GET /api/v1/imports/{jobId}} según el contrato. */
@RestController
class ImportController {

    private static final Logger log = LoggerFactory.getLogger(ImportController.class);

    static final int MAX_TITLE_LENGTH = 200;

    record ImportAccepted(UUID jobId, String status, String statusUrl) {}

    record ImportJob(
            UUID jobId,
            String kind,
            String status,
            @Nullable UUID activityId,
            @Nullable String errorCode,
            @Nullable String errorDetail,
            @JsonRawValue String warnings,
            List<UUID> possibleDuplicateActivityIds) {}

    private final OriginalFileStore store;
    private final ImportJobRepository jobs;
    private final CatalogLookup catalog;
    private final ImportProperties properties;
    private final Clock clock;

    ImportController(
            OriginalFileStore store,
            ImportJobRepository jobs,
            CatalogLookup catalog,
            ImportProperties properties,
            Clock clock) {
        this.store = store;
        this.jobs = jobs;
        this.catalog = catalog;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping("/api/v1/activities/imports")
    ResponseEntity<ImportAccepted> importActivity(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "bikeId", required = false) @Nullable UUID bikeId,
            @RequestParam(name = "deviceId", required = false) @Nullable UUID deviceId,
            @RequestParam(name = "activityTitle", required = false) @Nullable String activityTitle)
            throws IOException {
        if (file.isEmpty()) {
            throw new EmptyUploadException();
        }
        var title = activityTitle == null || activityTitle.isBlank() ? null : activityTitle.strip();
        if (title != null && title.length() > MAX_TITLE_LENGTH) {
            throw new InvalidImportTitleException();
        }
        if ((bikeId != null && catalog.bikeType(user.userId(), bikeId).isEmpty())
                || (deviceId != null && !catalog.deviceExists(user.userId(), deviceId))) {
            throw new ImportReferenceNotFoundException();
        }

        OriginalFileStore.StoredFile stored;
        try (var in = file.getInputStream()) {
            stored = store.store(user.userId(), in);
        } catch (FileTooLargeException e) {
            throw new FileTooLargeProblem();
        }
        var jobId = UUID.randomUUID();
        try {
            jobs.insert(
                    jobId,
                    user.userId(),
                    normalizeFileName(file.getOriginalFilename()),
                    stored.storageKey(),
                    stored.sha256(),
                    stored.sizeBytes(),
                    bikeId,
                    deviceId,
                    title,
                    properties.maxAttempts(),
                    clock.instant());
        } catch (RuntimeException e) {
            try {
                store.delete(stored.storageKey());
            } catch (IOException cleanup) {
                log.warn("No se pudo borrar un original sin trabajo asociado", cleanup);
            }
            throw e;
        }
        var statusUrl = "/api/v1/imports/" + jobId;
        return ResponseEntity.accepted()
                .location(java.net.URI.create(statusUrl))
                .body(new ImportAccepted(jobId, "QUEUED", statusUrl));
    }

    @GetMapping("/api/v1/imports/{jobId}")
    ImportJob job(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID jobId) {
        var view = jobs.find(user.userId(), jobId).orElseThrow(ImportJobNotFoundException::new);
        return new ImportJob(
                view.id(),
                view.kind(),
                view.status(),
                view.activityId(),
                view.errorCode(),
                view.errorDetail(),
                view.warningsJson(),
                view.possibleDuplicateIds());
    }

    /**
     * Nombre solo para mostrar: sin rutas, sin caracteres de control y acotado. Nunca se usa para guardar el
     * archivo (ADR-013).
     */
    static String normalizeFileName(@Nullable String original) {
        if (original == null || original.isBlank()) {
            return "archivo";
        }
        var name = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1);
        name = name.replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            return "archivo";
        }
        return name.length() <= 255 ? name : name.substring(0, 255);
    }

    static final class EmptyUploadException extends ErrorResponseException {
        EmptyUploadException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }

    static final class InvalidImportTitleException extends ErrorResponseException {
        InvalidImportTitleException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }

    static final class ImportReferenceNotFoundException extends ErrorResponseException {
        ImportReferenceNotFoundException() {
            super(HttpStatus.NOT_FOUND);
        }
    }

    static final class ImportJobNotFoundException extends ErrorResponseException {
        ImportJobNotFoundException() {
            super(HttpStatus.NOT_FOUND);
        }
    }

    static final class FileTooLargeProblem extends ErrorResponseException {
        FileTooLargeProblem() {
            super(HttpStatus.CONTENT_TOO_LARGE);
        }
    }
}
