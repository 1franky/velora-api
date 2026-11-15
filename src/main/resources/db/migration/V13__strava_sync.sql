-- Sincronización con Strava (ADR-030, fase 2).

-- Origen del trabajo: UPLOAD (archivo subido) o STRAVA (lo creó la sincronización). Solo los trabajos STRAVA se
-- leen con el parser de Strava; un JSON subido a mano no se convierte en actividad de Strava.
ALTER TABLE import_jobs ADD COLUMN source varchar(8) NOT NULL DEFAULT 'UPLOAD'
    CHECK (source IN ('UPLOAD', 'STRAVA'));

ALTER TABLE import_jobs DROP CONSTRAINT import_jobs_format_check;
ALTER TABLE import_jobs ADD CONSTRAINT import_jobs_format_check
    CHECK (format IN ('FIT', 'GPX', 'TCX', 'STRAVA'));
ALTER TABLE activity_files DROP CONSTRAINT activity_files_format_check;
ALTER TABLE activity_files ADD CONSTRAINT activity_files_format_check
    CHECK (format IN ('FIT', 'GPX', 'TCX', 'STRAVA'));

-- Avance de la sincronización por conexión.
--   synced_after:    lo más reciente ya revisado (la sincronización incremental pide lo posterior).
--   backfill_before: hasta dónde llegó la carga inicial, que avanza hacia atrás.
ALTER TABLE strava_connections
    ADD COLUMN synced_after     timestamptz,
    ADD COLUMN backfill_before  timestamptz,
    ADD COLUMN backfill_done    boolean      NOT NULL DEFAULT false,
    ADD COLUMN last_sync_at     timestamptz,
    ADD COLUMN last_sync_error  varchar(64);

-- Cada actividad de Strava revisada y qué se decidió, para no volver a pedirla ni reimportar lo que el usuario
-- borró en Velora.
CREATE TABLE strava_sync_items (
    user_id             uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    strava_activity_id  bigint       NOT NULL,
    outcome             varchar(16)  NOT NULL
        CHECK (outcome IN ('SUBMITTED', 'DUPLICATE', 'NOT_CYCLING', 'NO_DATA')),
    import_job_id       uuid,
    created_at          timestamptz  NOT NULL,
    PRIMARY KEY (user_id, strava_activity_id)
);
