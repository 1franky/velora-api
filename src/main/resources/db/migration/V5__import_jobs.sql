-- Trabajos de importación (ADR-009): status público del contrato + stage interno de 05-import-pipeline.

CREATE TABLE import_jobs (
    id                        uuid PRIMARY KEY,
    user_id                   uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status                    varchar(16)  NOT NULL
        CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'DUPLICATE', 'REJECTED', 'FAILED')),
    stage                     varchar(20)  NOT NULL
        CHECK (stage IN ('STORED', 'VALIDATING', 'PARSING', 'NORMALIZING', 'DEDUPLICATING', 'PERSISTING',
                         'DERIVING', 'COMPLETED', 'DUPLICATE', 'REJECTED', 'FAILED_RETRYABLE', 'FAILED_FINAL')),
    -- Nombre normalizado solo para mostrar; nunca se usa como ruta.
    file_name                 varchar(255) NOT NULL,
    format                    varchar(8)   CHECK (format IN ('FIT', 'GPX', 'TCX')),
    storage_key               varchar(200) NOT NULL,
    sha256                    bytea        NOT NULL CHECK (octet_length(sha256) = 32),
    file_size_bytes           bigint       NOT NULL CHECK (file_size_bytes > 0),
    bike_id                   uuid         REFERENCES bikes (id) ON DELETE SET NULL,
    device_id                 uuid         REFERENCES devices (id) ON DELETE SET NULL,
    activity_title            varchar(200),
    activity_id               uuid         REFERENCES activities (id) ON DELETE SET NULL,
    attempt_count             integer      NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts              integer      NOT NULL DEFAULT 3 CHECK (max_attempts >= 1),
    next_attempt_at           timestamptz  NOT NULL,
    locked_until              timestamptz,
    error_code                varchar(40),
    error_detail_safe         varchar(500),
    warnings                  jsonb        NOT NULL DEFAULT '[]'::jsonb,
    possible_duplicate_ids    uuid[]       NOT NULL DEFAULT '{}',
    created_at                timestamptz  NOT NULL,
    started_at                timestamptz,
    finished_at               timestamptz,
    updated_at                timestamptz  NOT NULL
);

-- Cola: trabajos listos para tomar (los PROCESSING con bloqueo vencido se recuperan aparte).
CREATE INDEX import_jobs_queue_idx ON import_jobs (next_attempt_at) WHERE status = 'QUEUED';
CREATE INDEX import_jobs_processing_idx ON import_jobs (locked_until) WHERE status = 'PROCESSING';
CREATE INDEX import_jobs_user_idx ON import_jobs (user_id, status, created_at);
