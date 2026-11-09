-- Catálogo: bicicletas y dispositivos de captura (02-domain-and-data-model.md).
-- Pertenecen a un usuario y se borran con él.

CREATE TABLE bikes (
    id          uuid PRIMARY KEY,
    user_id     uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        varchar(100) NOT NULL,
    bike_type   varchar(16)  NOT NULL CHECK (bike_type IN ('MTB', 'ROAD', 'GRAVEL', 'OTHER')),
    brand       varchar(100),
    model       varchar(100),
    -- Resolución de 10 g; rango acotado por el contrato (0, 50] kg.
    weight_kg   numeric(5, 2) CHECK (weight_kg > 0 AND weight_kg <= 50),
    active      boolean      NOT NULL DEFAULT true,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);

CREATE INDEX bikes_user_idx ON bikes (user_id, created_at);

CREATE TABLE devices (
    id            uuid PRIMARY KEY,
    user_id       uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    display_name  varchar(100) NOT NULL,
    manufacturer  varchar(100),
    model         varchar(100),
    created_at    timestamptz  NOT NULL
);

CREATE INDEX devices_user_idx ON devices (user_id, created_at);
