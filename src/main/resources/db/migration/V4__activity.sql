-- Modelo de actividad (ADR-012). Instantes en UTC; unidades SI.

CREATE TABLE activities (
    id                      uuid PRIMARY KEY,
    user_id                 uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Si se borra la bicicleta o el dispositivo, la actividad se conserva sin la referencia.
    bike_id                 uuid         REFERENCES bikes (id) ON DELETE SET NULL,
    device_id               uuid         REFERENCES devices (id) ON DELETE SET NULL,
    title                   varchar(200) NOT NULL,
    sport_type              varchar(16)  NOT NULL DEFAULT 'CYCLING' CHECK (sport_type IN ('CYCLING')),
    sub_type                varchar(16)  NOT NULL
        CHECK (sub_type IN ('MOUNTAIN_BIKE', 'ROAD', 'GRAVEL', 'INDOOR', 'OTHER')),
    started_at              timestamptz  NOT NULL,
    time_zone_at_import     varchar(64)  NOT NULL,
    local_start_date        date         NOT NULL,
    source_type             varchar(8)   NOT NULL CHECK (source_type IN ('FIT', 'GPX', 'TCX')),
    source_activity_key     varchar(200),
    -- Valores efectivos para listar y filtrar: prefieren el dato del dispositivo (ver activity_metrics).
    elapsed_time_seconds    numeric(12, 3) NOT NULL CHECK (elapsed_time_seconds >= 0),
    moving_time_seconds     numeric(12, 3) NOT NULL CHECK (moving_time_seconds >= 0),
    distance_meters         numeric(12, 2) CHECK (distance_meters >= 0),
    elevation_gain_meters   numeric(8, 1) CHECK (elevation_gain_meters >= 0),
    elevation_loss_meters   numeric(8, 1) CHECK (elevation_loss_meters >= 0),
    average_speed_mps       numeric(8, 3) CHECK (average_speed_mps >= 0),
    max_speed_mps           numeric(8, 3) CHECK (max_speed_mps >= 0),
    average_heart_rate_bpm  smallint,
    max_heart_rate_bpm      smallint,
    average_power_watts     smallint,
    max_power_watts         smallint,
    average_cadence_rpm     smallint,
    metrics_provenance      jsonb        NOT NULL DEFAULT '{}'::jsonb,
    processing_status       varchar(16)  NOT NULL DEFAULT 'READY' CHECK (processing_status IN ('READY')),
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL
);

CREATE INDEX activities_user_started_idx ON activities (user_id, started_at DESC, id DESC);
CREATE INDEX activities_user_sport_started_idx ON activities (user_id, sport_type, started_at DESC);
CREATE INDEX activities_user_source_key_idx ON activities (user_id, source_activity_key)
    WHERE source_activity_key IS NOT NULL;

-- Métricas guardadas por separado según su origen (AGENTS.md: conservar originales y calculadas).
CREATE TABLE activity_metrics (
    activity_id        uuid        NOT NULL REFERENCES activities (id) ON DELETE CASCADE,
    metric             varchar(40) NOT NULL,
    source             varchar(16) NOT NULL CHECK (source IN ('DEVICE_REPORTED', 'DERIVED')),
    value              numeric(14, 3) NOT NULL,
    algorithm_version  varchar(80),
    parameters         jsonb,
    PRIMARY KEY (activity_id, metric, source),
    CHECK ((source = 'DERIVED') = (algorithm_version IS NOT NULL))
);

CREATE TABLE activity_tracks (
    activity_id  uuid PRIMARY KEY REFERENCES activities (id) ON DELETE CASCADE,
    -- Un tramo por segmento: las pausas no se dibujan como rectas.
    route_geom   geometry(MultiLineString, 4326) NOT NULL,
    start_point  geometry(Point, 4326) NOT NULL,
    end_point    geometry(Point, 4326) NOT NULL,
    bbox         geometry(Polygon, 4326) NOT NULL,
    point_count  integer NOT NULL CHECK (point_count >= 2)
);

CREATE INDEX activity_tracks_route_gist ON activity_tracks USING gist (route_geom);

CREATE TABLE activity_laps (
    activity_id             uuid     NOT NULL REFERENCES activities (id) ON DELETE CASCADE,
    lap_number              smallint NOT NULL CHECK (lap_number >= 1),
    started_at              timestamptz,
    elapsed_time_seconds    numeric(12, 3),
    timer_time_seconds      numeric(12, 3),
    distance_meters         numeric(12, 2),
    average_speed_mps       numeric(8, 3),
    max_speed_mps           numeric(8, 3),
    average_heart_rate_bpm  smallint,
    max_heart_rate_bpm      smallint,
    average_cadence_rpm     smallint,
    average_power_watts     smallint,
    max_power_watts         smallint,
    PRIMARY KEY (activity_id, lap_number)
);

-- Streams codificados con velora-streams/1 (ADR-012); ya comprimidos, sin recompresión en TOAST.
CREATE TABLE activity_streams (
    activity_id   uuid        NOT NULL REFERENCES activities (id) ON DELETE CASCADE,
    stream_type   varchar(16) NOT NULL
        CHECK (stream_type IN ('TIME', 'LATITUDE', 'LONGITUDE', 'ALTITUDE', 'DISTANCE', 'SPEED',
                               'HEART_RATE', 'CADENCE', 'POWER', 'TEMPERATURE')),
    sample_count  integer     NOT NULL CHECK (sample_count >= 0),
    encoding      varchar(32) NOT NULL,
    payload       bytea       NOT NULL,
    PRIMARY KEY (activity_id, stream_type)
);

ALTER TABLE activity_streams ALTER COLUMN payload SET STORAGE EXTERNAL;

CREATE TABLE activity_files (
    id               uuid PRIMARY KEY,
    activity_id      uuid        NOT NULL REFERENCES activities (id) ON DELETE CASCADE,
    user_id          uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    format           varchar(8)  NOT NULL CHECK (format IN ('FIT', 'GPX', 'TCX')),
    storage_key      varchar(200) NOT NULL UNIQUE,
    sha256           bytea       NOT NULL CHECK (octet_length(sha256) = 32),
    file_size_bytes  bigint      NOT NULL CHECK (file_size_bytes > 0),
    parser_name      varchar(40) NOT NULL,
    parser_version   varchar(80) NOT NULL,
    imported_at      timestamptz NOT NULL
);

-- Primer nivel de deduplicación: el mismo archivo no se importa dos veces por usuario.
CREATE UNIQUE INDEX activity_files_user_sha256_uq ON activity_files (user_id, sha256);
CREATE INDEX activity_files_activity_idx ON activity_files (activity_id);
