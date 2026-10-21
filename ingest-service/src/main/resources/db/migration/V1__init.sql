CREATE TABLE observations (
    id             BIGSERIAL PRIMARY KEY,
    sensor_id      TEXT             NOT NULL,
    ts             TIMESTAMPTZ      NOT NULL,
    frequency_mhz  DOUBLE PRECISION NOT NULL,
    bandwidth_khz  DOUBLE PRECISION NOT NULL,
    power_dbm      DOUBLE PRECISION NOT NULL,
    lat            DOUBLE PRECISION NOT NULL,
    lon            DOUBLE PRECISION NOT NULL,
    received_at    TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX idx_observations_sensor_ts ON observations (sensor_id, ts);
CREATE INDEX idx_observations_ts ON observations (ts);
CREATE INDEX idx_observations_freq_ts ON observations (frequency_mhz, ts);

CREATE TABLE rules (
    rule_id          TEXT PRIMARY KEY,
    name             TEXT             NOT NULL,
    type             TEXT             NOT NULL,
    min_mhz          DOUBLE PRECISION NOT NULL,
    max_mhz          DOUBLE PRECISION NOT NULL,
    threshold_dbm    DOUBLE PRECISION NOT NULL,
    min_duration_s   INTEGER          NOT NULL,
    resolve_after_s  INTEGER          NOT NULL,
    severity         TEXT             NOT NULL,
    enabled          BOOLEAN          NOT NULL DEFAULT TRUE
);
