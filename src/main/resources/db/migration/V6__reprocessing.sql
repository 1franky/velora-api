-- Reprocesamiento (05-import-pipeline.md §7): idempotente, versionado y comparable.

ALTER TABLE import_jobs
    ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'IMPORT' CHECK (kind IN ('IMPORT', 'REPROCESS'));

-- Como máximo un reproceso pendiente por actividad: pedirlo de nuevo devuelve el mismo trabajo.
CREATE UNIQUE INDEX import_jobs_pending_reprocess_uq ON import_jobs (activity_id)
    WHERE kind = 'REPROCESS' AND status IN ('QUEUED', 'PROCESSING');

-- Valores anteriores de cada métrica, para comparar con los nuevos tras un reproceso.
CREATE TABLE activity_metrics_history (
    activity_id        uuid           NOT NULL REFERENCES activities (id) ON DELETE CASCADE,
    metric             varchar(40)    NOT NULL,
    source             varchar(16)    NOT NULL,
    value              numeric(14, 3) NOT NULL,
    algorithm_version  varchar(80),
    parameters         jsonb,
    parser_version     varchar(80)    NOT NULL,
    superseded_at      timestamptz    NOT NULL
);

CREATE INDEX activity_metrics_history_idx ON activity_metrics_history (activity_id, superseded_at);
