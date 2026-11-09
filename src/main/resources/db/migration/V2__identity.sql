-- Identidad y sesiones (ADR-010). Instantes en UTC (timestamptz).

CREATE TABLE users (
    id              uuid PRIMARY KEY,
    email           varchar(254) NOT NULL,
    password_hash   varchar(255) NOT NULL,
    locale          varchar(16)  NOT NULL DEFAULT 'es-MX',
    time_zone       varchar(64)  NOT NULL DEFAULT 'America/Mexico_City',
    distance_unit   varchar(8)   NOT NULL DEFAULT 'km',
    elevation_unit  varchar(8)   NOT NULL DEFAULT 'm',
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now()
);

-- Correo único sin distinguir mayúsculas.
CREATE UNIQUE INDEX users_email_lower_uq ON users (lower(email));

-- Una sesión agrupa la familia de tokens emitidos desde un login; revocarla invalida todos.
CREATE TABLE auth_sessions (
    id              uuid PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    revoked_reason  varchar(32)
);

CREATE INDEX auth_sessions_user_idx ON auth_sessions (user_id);

-- Solo se guarda el SHA-256 del token, nunca el token.
CREATE TABLE auth_tokens (
    token_hash  bytea       PRIMARY KEY CHECK (octet_length(token_hash) = 32),
    session_id  uuid        NOT NULL REFERENCES auth_sessions (id) ON DELETE CASCADE,
    kind        varchar(8)  NOT NULL CHECK (kind IN ('ACCESS', 'REFRESH')),
    issued_at   timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    -- Refresh tokens: instante en que se rotaron. Reutilizar uno rotado revoca la sesión.
    used_at     timestamptz
);

CREATE INDEX auth_tokens_session_idx ON auth_tokens (session_id);
CREATE INDEX auth_tokens_expires_idx ON auth_tokens (expires_at);
