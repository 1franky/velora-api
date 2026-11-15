-- Webhooks de Strava (ADR-030, fase 3). Los eventos se guardan al recibirlos y se procesan después: Strava exige
-- respuesta en menos de 2 s, y así un borrado sobrevive a un reinicio (compromiso de borrar en menos de 48 h).
CREATE TABLE strava_webhook_events (
    id            bigserial    PRIMARY KEY,
    received_at   timestamptz  NOT NULL,
    owner_id      bigint       NOT NULL,
    object_type   varchar(16)  NOT NULL,
    object_id     bigint       NOT NULL,
    aspect_type   varchar(16)  NOT NULL,
    title         varchar(300),
    authorized    boolean,
    attempts      integer      NOT NULL DEFAULT 0,
    processed_at  timestamptz
);

CREATE INDEX strava_webhook_events_pending_idx ON strava_webhook_events (id) WHERE processed_at IS NULL;

-- DELETED: borrada en Strava; la sincronización ya no la trae aunque no se hubiera visto antes.
ALTER TABLE strava_sync_items DROP CONSTRAINT strava_sync_items_outcome_check;
ALTER TABLE strava_sync_items ADD CONSTRAINT strava_sync_items_outcome_check
    CHECK (outcome IN ('SUBMITTED', 'DUPLICATE', 'NOT_CYCLING', 'NO_DATA', 'DELETED'));
