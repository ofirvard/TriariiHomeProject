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

-- last_seen is event time, upserted with GREATEST() so out-of-order arrivals never move it backwards.
CREATE TABLE sensors (
    sensor_id  TEXT PRIMARY KEY,
    last_seen  TIMESTAMPTZ      NOT NULL,
    lat        DOUBLE PRECISION NOT NULL,
    lon        DOUBLE PRECISION NOT NULL
);

-- One table for all rule types; type-specific columns are nullable.
CREATE TABLE rules (
    rule_id          TEXT PRIMARY KEY,
    name             TEXT             NOT NULL,
    type             TEXT             NOT NULL,
    min_mhz          DOUBLE PRECISION NOT NULL,
    max_mhz          DOUBLE PRECISION NOT NULL,
    severity         TEXT             NOT NULL,
    enabled          BOOLEAN          NOT NULL DEFAULT TRUE,
    resolve_after_s  INTEGER          NOT NULL,
    -- power_threshold
    threshold_dbm    DOUBLE PRECISION,
    min_duration_s   INTEGER,
    -- multi_sensor
    tolerance_khz    DOUBLE PRECISION,
    min_sensors      INTEGER,
    window_s         INTEGER,
    updated_at       TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CHECK (min_mhz < max_mhz)
);
