CREATE TABLE alerts (
    alert_id         TEXT PRIMARY KEY,
    rule_id          TEXT             NOT NULL,
    signal_key       TEXT             NOT NULL,
    state            TEXT             NOT NULL CHECK (state IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    severity         TEXT             NOT NULL,
    frequency_mhz    DOUBLE PRECISION NOT NULL,
    sensor_ids       TEXT[]           NOT NULL,
    first_seen       TIMESTAMPTZ      NOT NULL,  -- event time
    last_seen        TIMESTAMPTZ      NOT NULL,  -- event time
    occurrences      INTEGER          NOT NULL,
    peak_power_dbm   DOUBLE PRECISION NOT NULL,
    resolve_after_s  INTEGER          NOT NULL,
    acknowledged_at  TIMESTAMPTZ,
    resolved_at      TIMESTAMPTZ,
    resolved_reason  TEXT CHECK (resolved_reason IN ('AUTO', 'MANUAL')),
    created_at       TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ      NOT NULL DEFAULT now()
);

-- Dedup guarantee enforced by the database: at most one OPEN/ACKNOWLEDGED alert per rule + signal.
CREATE UNIQUE INDEX uq_alerts_active_signal ON alerts (signal_key) WHERE state <> 'RESOLVED';
CREATE INDEX idx_alerts_state ON alerts (state);
CREATE INDEX idx_alerts_severity ON alerts (severity);
CREATE INDEX idx_alerts_frequency ON alerts (frequency_mhz);
CREATE INDEX idx_alerts_last_seen ON alerts (last_seen);
CREATE INDEX idx_alerts_sensor_ids ON alerts USING GIN (sensor_ids);

-- Copies of the contributing observations, so alert details never need to call ingest-service.
CREATE TABLE alert_observations (
    alert_id        TEXT             NOT NULL REFERENCES alerts (alert_id) ON DELETE CASCADE,
    observation_id  BIGINT           NOT NULL,
    sensor_id       TEXT             NOT NULL,
    ts              TIMESTAMPTZ      NOT NULL,
    frequency_mhz   DOUBLE PRECISION NOT NULL,
    bandwidth_khz   DOUBLE PRECISION NOT NULL,
    power_dbm       DOUBLE PRECISION NOT NULL,
    lat             DOUBLE PRECISION NOT NULL,
    lon             DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (alert_id, observation_id)
);

CREATE INDEX idx_alert_observations_ts ON alert_observations (alert_id, ts);

-- Idempotency for at-least-once delivery: an event id is applied at most once.
CREATE TABLE processed_events (
    event_id      TEXT PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
