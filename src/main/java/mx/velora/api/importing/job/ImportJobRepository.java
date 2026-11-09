package mx.velora.api.importing.job;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistencia y cola de {@code import_jobs} (ADR-009). */
@Repository
public class ImportJobRepository {

    /** Trabajo tomado por el worker. */
    public record ClaimedJob(
            UUID id,
            UUID userId,
            String storageKey,
            byte[] sha256,
            long sizeBytes,
            @Nullable UUID bikeId,
            @Nullable UUID deviceId,
            @Nullable String activityTitle,
            int attemptCount,
            int maxAttempts,
            String kind,
            @Nullable UUID activityId) {

        public boolean isReprocess() {
            return "REPROCESS".equals(kind);
        }
    }

    /** Estado visible para el usuario (contrato {@code ImportJob}). */
    public record JobView(
            UUID id,
            String kind,
            String status,
            @Nullable UUID activityId,
            @Nullable String errorCode,
            @Nullable String errorDetail,
            String warningsJson,
            List<UUID> possibleDuplicateIds) {}

    private final JdbcClient jdbc;

    ImportJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            UUID id,
            UUID userId,
            String fileName,
            String storageKey,
            byte[] sha256,
            long sizeBytes,
            @Nullable UUID bikeId,
            @Nullable UUID deviceId,
            @Nullable String activityTitle,
            int maxAttempts,
            Instant now) {
        jdbc.sql("""
                        INSERT INTO import_jobs (
                            id, user_id, status, stage, file_name, storage_key, sha256, file_size_bytes,
                            bike_id, device_id, activity_title, max_attempts, next_attempt_at, created_at, updated_at)
                        VALUES (
                            :id, :user, 'QUEUED', 'STORED', :name, :key, :sha, :size,
                            :bike, :device, :title, :maxAttempts, :now, :now, :now)
                        """)
                .param("id", id)
                .param("user", userId)
                .param("name", fileName)
                .param("key", storageKey)
                .param("sha", sha256)
                .param("size", sizeBytes)
                .param("bike", bikeId, Types.OTHER)
                .param("device", deviceId, Types.OTHER)
                .param("title", activityTitle, Types.VARCHAR)
                .param("maxAttempts", maxAttempts)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Reproceso pendiente (en cola o en proceso) de una actividad, si existe. */
    public Optional<UUID> pendingReprocess(UUID userId, UUID activityId) {
        return jdbc.sql("""
                        SELECT id FROM import_jobs
                        WHERE user_id = :user AND activity_id = :activity AND kind = 'REPROCESS'
                          AND status IN ('QUEUED', 'PROCESSING')
                        """)
                .param("user", userId)
                .param("activity", activityId)
                .query(UUID.class)
                .optional();
    }

    public void insertReprocess(
            UUID id,
            UUID userId,
            UUID activityId,
            String storageKey,
            byte[] sha256,
            long sizeBytes,
            @Nullable UUID bikeId,
            int maxAttempts,
            Instant now) {
        jdbc.sql("""
                        INSERT INTO import_jobs (
                            id, user_id, kind, status, stage, file_name, storage_key, sha256, file_size_bytes,
                            bike_id, activity_id, max_attempts, next_attempt_at, created_at, updated_at)
                        VALUES (
                            :id, :user, 'REPROCESS', 'QUEUED', 'STORED', 'reprocesamiento', :key, :sha, :size,
                            :bike, :activity, :maxAttempts, :now, :now, :now)
                        """)
                .param("id", id)
                .param("user", userId)
                .param("key", storageKey)
                .param("sha", sha256)
                .param("size", sizeBytes)
                .param("bike", bikeId, Types.OTHER)
                .param("activity", activityId)
                .param("maxAttempts", maxAttempts)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * Toma hasta {@code batch} trabajos listos, o en proceso con el bloqueo vencido (p. ej. tras un reinicio), sin
     * esperar a los que otro worker ya tiene ({@code FOR UPDATE SKIP LOCKED}).
     */
    public List<ClaimedJob> claim(Instant now, Duration lockDuration, int batch) {
        return jdbc.sql("""
                        UPDATE import_jobs j SET
                            status = 'PROCESSING', stage = 'VALIDATING', attempt_count = j.attempt_count + 1,
                            locked_until = :lockedUntil, started_at = coalesce(j.started_at, :now), updated_at = :now
                        WHERE j.id IN (
                            SELECT id FROM import_jobs
                            WHERE (status = 'QUEUED' AND next_attempt_at <= :now)
                               OR (status = 'PROCESSING' AND locked_until < :now AND attempt_count < max_attempts)
                            ORDER BY next_attempt_at, created_at
                            LIMIT :batch
                            FOR UPDATE SKIP LOCKED)
                        RETURNING j.id, j.user_id, j.storage_key, j.sha256, j.file_size_bytes, j.bike_id, j.device_id,
                                  j.activity_title, j.attempt_count, j.max_attempts, j.kind, j.activity_id
                        """)
                .param("lockedUntil", Timestamp.from(now.plus(lockDuration)))
                .param("now", Timestamp.from(now))
                .param("batch", batch)
                .query((rs, n) -> new ClaimedJob(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("storage_key"),
                        rs.getBytes("sha256"),
                        rs.getLong("file_size_bytes"),
                        rs.getObject("bike_id", UUID.class),
                        rs.getObject("device_id", UUID.class),
                        rs.getString("activity_title"),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"),
                        rs.getString("kind"),
                        rs.getObject("activity_id", UUID.class)))
                .list();
    }

    /** Trabajos huérfanos que ya agotaron sus intentos (el proceso cayó en cada uno): se dan por fallidos. */
    public int failAbandoned(Instant now) {
        return jdbc.sql("""
                        UPDATE import_jobs SET status = 'FAILED', stage = 'FAILED_FINAL', error_code = 'INTERNAL_ERROR',
                            error_detail_safe = :detail, locked_until = NULL, finished_at = :now, updated_at = :now
                        WHERE status = 'PROCESSING' AND locked_until < :now AND attempt_count >= max_attempts
                        """)
                .param("detail", ImportMessages.INTERNAL_ERROR)
                .param("now", Timestamp.from(now))
                .update();
    }

    public void complete(
            UUID id,
            UUID activityId,
            @Nullable String format,
            String warningsJson,
            List<UUID> possibleDuplicates,
            Instant now) {
        jdbc.sql("""
                        UPDATE import_jobs SET status = 'COMPLETED', stage = 'COMPLETED', activity_id = :activity,
                            format = :format, warnings = CAST(:warnings AS jsonb), possible_duplicate_ids = :duplicates,
                            error_code = NULL, error_detail_safe = NULL, locked_until = NULL,
                            finished_at = :now, updated_at = :now
                        WHERE id = :id
                        """)
                .param("activity", activityId)
                .param("format", format, Types.VARCHAR)
                .param("warnings", warningsJson)
                .param("duplicates", possibleDuplicates.toArray(UUID[]::new))
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    public void duplicate(UUID id, UUID existingActivityId, @Nullable String format, Instant now) {
        jdbc.sql("""
                        UPDATE import_jobs SET status = 'DUPLICATE', stage = 'DUPLICATE', activity_id = :activity,
                            format = :format, locked_until = NULL, finished_at = :now, updated_at = :now
                        WHERE id = :id
                        """)
                .param("activity", existingActivityId)
                .param("format", format, Types.VARCHAR)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    public void reject(UUID id, String errorCode, String detail, Instant now) {
        finish(id, "REJECTED", "REJECTED", errorCode, detail, now);
    }

    public void fail(UUID id, String errorCode, String detail, Instant now) {
        finish(id, "FAILED", "FAILED_FINAL", errorCode, detail, now);
    }

    /** Vuelve a la cola: el {@code status} público es {@code QUEUED} y el {@code stage} interno lo distingue. */
    public void retryLater(UUID id, String errorCode, String detail, Instant nextAttemptAt, Instant now) {
        jdbc.sql("""
                        UPDATE import_jobs SET status = 'QUEUED', stage = 'FAILED_RETRYABLE', error_code = :code,
                            error_detail_safe = :detail, next_attempt_at = :next, locked_until = NULL, updated_at = :now
                        WHERE id = :id
                        """)
                .param("code", errorCode)
                .param("detail", detail)
                .param("next", Timestamp.from(nextAttemptAt))
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    private void finish(UUID id, String status, String stage, String errorCode, String detail, Instant now) {
        jdbc.sql("""
                        UPDATE import_jobs SET status = :status, stage = :stage, error_code = :code,
                            error_detail_safe = :detail, locked_until = NULL, finished_at = :now, updated_at = :now
                        WHERE id = :id
                        """)
                .param("status", status)
                .param("stage", stage)
                .param("code", errorCode)
                .param("detail", detail.length() > 500 ? detail.substring(0, 500) : detail)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    public Optional<JobView> find(UUID userId, UUID jobId) {
        return jdbc.sql("""
                        SELECT id, kind, status, activity_id, error_code, error_detail_safe, warnings::text AS warnings,
                               possible_duplicate_ids
                        FROM import_jobs WHERE id = :id AND user_id = :user
                        """)
                .param("id", jobId)
                .param("user", userId)
                .query(ImportJobRepository::view)
                .optional();
    }

    private static JobView view(ResultSet rs, int row) throws SQLException {
        Array ids = rs.getArray("possible_duplicate_ids");
        var duplicates = ids == null ? List.<UUID>of() : Arrays.asList((UUID[]) ids.getArray());
        return new JobView(
                rs.getObject("id", UUID.class),
                rs.getString("kind"),
                rs.getString("status"),
                rs.getObject("activity_id", UUID.class),
                rs.getString("error_code"),
                rs.getString("error_detail_safe"),
                rs.getString("warnings"),
                duplicates);
    }
}
