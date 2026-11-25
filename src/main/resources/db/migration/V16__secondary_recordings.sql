-- Grabaciones secundarias (ADR-032): archivos de otro dispositivo (primero el Watch Companion) que se guardan junto a
-- una actividad sin ser una actividad. No participan en analítica, mapa de calor, exploración ni récords.
-- Una fila existe solo cuando el archivo ya se procesó; la espera y los rechazos viven en import_jobs.

CREATE TABLE secondary_recordings (
    id                      uuid PRIMARY KEY,
    user_id                 uuid           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Al borrar la actividad (por el usuario o por un delete de Strava) la grabación queda sin asignar, no se borra.
    activity_id             uuid           REFERENCES activities (id) ON DELETE SET NULL,
    source                  varchar(16)    NOT NULL CHECK (source IN ('WATCH')),
    -- Id que genera el dispositivo al empezar a grabar: clave de idempotencia de la subida.
    client_recording_id     uuid           NOT NULL,
    -- Última decisión de asignación: AUTO (por solape de tiempos) o USER. La automática nunca mueve una USER.
    attached_by             varchar(8)     CHECK (attached_by IN ('AUTO', 'USER')),
    completeness            varchar(8)     NOT NULL CHECK (completeness IN ('COMPLETE', 'PARTIAL')),
    format                  varchar(8)     NOT NULL CHECK (format IN ('FIT', 'GPX', 'TCX')),
    storage_key             varchar(200)   NOT NULL UNIQUE,
    sha256                  bytea          NOT NULL CHECK (octet_length(sha256) = 32),
    file_size_bytes         bigint         NOT NULL CHECK (file_size_bytes > 0),
    parser_name             varchar(40)    NOT NULL,
    parser_version          varchar(80)    NOT NULL,
    started_at              timestamptz    NOT NULL,
    ended_at                timestamptz    NOT NULL CHECK (ended_at >= started_at),
    elapsed_time_seconds    numeric(12, 3) NOT NULL CHECK (elapsed_time_seconds >= 0),
    distance_meters         numeric(12, 2) CHECK (distance_meters >= 0),
    point_count             integer        NOT NULL CHECK (point_count >= 0),
    average_heart_rate_bpm  smallint,
    max_heart_rate_bpm      smallint,
    received_at             timestamptz    NOT NULL,
    updated_at              timestamptz    NOT NULL,
    CHECK (activity_id IS NULL OR attached_by IS NOT NULL)
);

CREATE UNIQUE INDEX secondary_recordings_client_uq ON secondary_recordings (user_id, source, client_recording_id);
CREATE UNIQUE INDEX secondary_recordings_sha_uq ON secondary_recordings (user_id, sha256);
CREATE INDEX secondary_recordings_activity_idx ON secondary_recordings (activity_id) WHERE activity_id IS NOT NULL;
CREATE INDEX secondary_recordings_unassigned_idx ON secondary_recordings (user_id, started_at) WHERE activity_id IS NULL;

-- Series y recorrido con la misma forma que los de la actividad (ADR-012); no alimentan derivados geoespaciales.
CREATE TABLE secondary_recording_streams (
    recording_id  uuid        NOT NULL REFERENCES secondary_recordings (id) ON DELETE CASCADE,
    stream_type   varchar(16) NOT NULL
        CHECK (stream_type IN ('TIME', 'LATITUDE', 'LONGITUDE', 'ALTITUDE', 'DISTANCE', 'SPEED',
                               'HEART_RATE', 'CADENCE', 'POWER', 'TEMPERATURE')),
    sample_count  integer     NOT NULL CHECK (sample_count >= 0),
    encoding      varchar(32) NOT NULL,
    payload       bytea       NOT NULL,
    PRIMARY KEY (recording_id, stream_type)
);

ALTER TABLE secondary_recording_streams ALTER COLUMN payload SET STORAGE EXTERNAL;

CREATE TABLE secondary_recording_tracks (
    recording_id  uuid PRIMARY KEY REFERENCES secondary_recordings (id) ON DELETE CASCADE,
    route_geom    geometry(MultiLineString, 4326) NOT NULL,
    bbox          geometry(Polygon, 4326) NOT NULL,
    point_count   integer NOT NULL CHECK (point_count >= 2)
);

-- La subida y la recuperación como actividad usan la cola de importación (asíncrona e idempotente).
ALTER TABLE import_jobs DROP CONSTRAINT import_jobs_kind_check;
ALTER TABLE import_jobs ADD CONSTRAINT import_jobs_kind_check
    CHECK (kind IN ('IMPORT', 'REPROCESS', 'SECONDARY', 'PROMOTE'));

ALTER TABLE import_jobs
    ADD COLUMN client_recording_id uuid,
    ADD COLUMN recording_completeness varchar(8) CHECK (recording_completeness IN ('COMPLETE', 'PARTIAL')),
    ADD COLUMN secondary_recording_id uuid REFERENCES secondary_recordings (id) ON DELETE SET NULL;

ALTER TABLE import_jobs ADD CONSTRAINT import_jobs_secondary_fields_check
    CHECK (kind <> 'SECONDARY' OR (client_recording_id IS NOT NULL AND recording_completeness IS NOT NULL));

-- Como máximo una subida pendiente por grabación del dispositivo y una recuperación pendiente por grabación.
CREATE UNIQUE INDEX import_jobs_pending_secondary_uq ON import_jobs (user_id, client_recording_id)
    WHERE kind = 'SECONDARY' AND status IN ('QUEUED', 'PROCESSING');
CREATE UNIQUE INDEX import_jobs_pending_promote_uq ON import_jobs (secondary_recording_id)
    WHERE kind = 'PROMOTE' AND status IN ('QUEUED', 'PROCESSING');
