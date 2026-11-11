-- Registro persistente de eventos de Spring Modulith (esquema v2 de spring-modulith-events-jdbc 2.1): los listeners
-- entre módulos que fallen o queden a medias por un reinicio se reintentan (ADR-018).
CREATE TABLE event_publication (
    id                     uuid                     NOT NULL PRIMARY KEY,
    listener_id            text                     NOT NULL,
    event_type             text                     NOT NULL,
    serialized_event       text                     NOT NULL,
    publication_date       timestamp with time zone NOT NULL,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    integer,
    last_resubmission_date timestamp with time zone
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);

-- Celdas de exploración (ADR-018), derivadas del recorrido ya recortado por la privacidad de inicio y fin.
-- Una fila por actividad con la versión y los parámetros del cálculo; sirve también para saber qué recalcular.
CREATE TABLE exploration_runs (
    activity_id        uuid         PRIMARY KEY REFERENCES activities (id) ON DELETE CASCADE,
    user_id            uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    algorithm_version  varchar(40)  NOT NULL,
    parameters         jsonb        NOT NULL,
    -- Privacidad vigente al calcular: si el usuario la cambia, la actividad se recalcula.
    privacy_meters     integer      NOT NULL,
    computed_at        timestamptz  NOT NULL
);

-- grid: H3 (índice H3 de 64 bits) o MX1KM (cuadrícula de 1 km en EPSG:6372, solo México; cell = col * 2^20 + fila).
CREATE TABLE exploration_cells (
    activity_id  uuid       NOT NULL REFERENCES activities (id) ON DELETE CASCADE,
    user_id      uuid       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    grid         varchar(8) NOT NULL CHECK (grid IN ('H3', 'MX1KM')),
    resolution   integer    NOT NULL,
    cell         bigint     NOT NULL,
    PRIMARY KEY (activity_id, grid, resolution, cell)
);

CREATE INDEX exploration_cells_user_idx ON exploration_cells (user_id, grid, resolution, cell);
