-- An alert row holds identity and state; everything else is derived from its observations (alert_view).
CREATE TABLE alerts (
    alert_id         TEXT PRIMARY KEY,
    rule_id          TEXT             NOT NULL,
    signal_key       TEXT             NOT NULL,  -- dedup key: rule + frequency bucket
    state            TEXT             NOT NULL CHECK (state IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    severity         TEXT             NOT NULL,
    frequency_mhz    DOUBLE PRECISION NOT NULL,
    resolve_after_s  INTEGER          NOT NULL
);

-- Dedup guarantee enforced by the database: at most one OPEN/ACKNOWLEDGED alert per signal.
CREATE UNIQUE INDEX uq_alerts_active_signal ON alerts (signal_key) WHERE state <> 'RESOLVED';

-- Contributing observations. The primary key makes a redelivered event a no-op.
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

-- The alert as the API returns it. first_seen / last_seen are event time.
CREATE VIEW alert_view AS
SELECT a.alert_id, a.rule_id, a.state, a.severity, a.frequency_mhz,
       array_agg(DISTINCT o.sensor_id ORDER BY o.sensor_id) AS sensor_ids,
       min(o.ts)          AS first_seen,
       max(o.ts)          AS last_seen,
       count(*)::int      AS occurrences,
       max(o.power_dbm)   AS peak_power_dbm
FROM alerts a
JOIN alert_observations o ON o.alert_id = a.alert_id
GROUP BY a.alert_id;
