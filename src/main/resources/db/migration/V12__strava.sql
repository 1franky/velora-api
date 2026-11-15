-- Conector de Strava (ADR-030). Las actividades importadas de Strava se marcan con source_type = 'STRAVA'.

ALTER TABLE activities DROP CONSTRAINT activities_source_type_check;
ALTER TABLE activities ADD CONSTRAINT activities_source_type_check
    CHECK (source_type IN ('FIT', 'GPX', 'TCX', 'STRAVA'));

-- Una conexión por usuario. Los tokens van cifrados con AES-GCM (velora.strava.token-key); nunca en claro.
CREATE TABLE strava_connections (
    user_id            uuid         PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    athlete_id         bigint       NOT NULL UNIQUE,
    scope              varchar(200) NOT NULL,
    access_token       bytea        NOT NULL,
    refresh_token      bytea        NOT NULL,
    access_expires_at  timestamptz  NOT NULL,
    connected_at       timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL
);

-- Estado OAuth de un solo uso contra CSRF: solo su SHA-256, vence en minutos.
CREATE TABLE strava_oauth_states (
    state_hash  bytea        PRIMARY KEY,
    user_id     uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at  timestamptz  NOT NULL
);
