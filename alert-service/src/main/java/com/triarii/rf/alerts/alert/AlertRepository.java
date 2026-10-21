package com.triarii.rf.alerts.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triarii.rf.common.LocationModel;
import com.triarii.rf.common.ObservationModel;
import com.triarii.rf.common.Severity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class AlertRepository {

    private static final String COLUMNS = """
            alert_id, rule_id, state, severity, frequency_mhz, sensor_ids, first_seen, last_seen, occurrences,
            peak_power_dbm
            """;

    // sensor_ids travel as a JSON array: NamedParameterJdbcTemplate would expand a Java array into "?, ?, ?".
    private static final String SENSOR_ARRAY = "ARRAY(SELECT jsonb_array_elements_text(CAST(:sensorIds AS jsonb)))";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AlertRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** @return false if this event id was already applied (redelivery) */
    public boolean markProcessed(String eventId) {
        return jdbc.update("INSERT INTO processed_events (event_id) VALUES (:eventId) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource("eventId", eventId)) == 1;
    }

    /** The OPEN/ACKNOWLEDGED alert for a signal, row-locked until the transaction ends. */
    public Optional<AlertModel> findActiveForUpdate(String signalKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM alerts WHERE signal_key = :signalKey"
                        + " AND state <> 'RESOLVED' FOR UPDATE",
                new MapSqlParameterSource("signalKey", signalKey), this::map).stream().findFirst();
    }

    public Optional<AlertModel> findById(String alertId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM alerts WHERE alert_id = :alertId",
                new MapSqlParameterSource("alertId", alertId), this::map).stream().findFirst();
    }

    /** signal_key and resolve_after_s are stored for dedup and auto-resolve; they are not part of the model. */
    public void insert(AlertModel alert, String signalKey, long resolveAfterS) {
        jdbc.update("INSERT INTO alerts (alert_id, rule_id, signal_key, state, severity, frequency_mhz, sensor_ids,"
                + " first_seen, last_seen, occurrences, peak_power_dbm, resolve_after_s)"
                + " VALUES (:alertId, :ruleId, :signalKey, :state, :severity, :frequencyMhz, " + SENSOR_ARRAY + ","
                + " :firstSeen, :lastSeen, :occurrences, :peakPowerDbm, :resolveAfterS)",
                params(alert).addValue("signalKey", signalKey).addValue("resolveAfterS", resolveAfterS));
    }

    public void updateAggregates(AlertModel alert) {
        jdbc.update("UPDATE alerts SET sensor_ids = " + SENSOR_ARRAY + ", first_seen = :firstSeen,"
                + " last_seen = :lastSeen, occurrences = :occurrences, peak_power_dbm = :peakPowerDbm,"
                + " updated_at = now() WHERE alert_id = :alertId", params(alert));
    }

    public void addObservations(String alertId, List<ObservationModel> observations) {
        SqlParameterSource[] params = observations.stream()
                .map(o -> new MapSqlParameterSource()
                        .addValue("alertId", alertId)
                        .addValue("observationId", o.id())
                        .addValue("sensorId", o.sensorId())
                        .addValue("ts", utc(o.timestamp()))
                        .addValue("frequencyMhz", o.frequencyMhz())
                        .addValue("bandwidthKhz", o.bandwidthKhz())
                        .addValue("powerDbm", o.powerDbm())
                        .addValue("lat", o.location().lat())
                        .addValue("lon", o.location().lon()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO alert_observations
                    (alert_id, observation_id, sensor_id, ts, frequency_mhz, bandwidth_khz, power_dbm, lat, lon)
                VALUES (:alertId, :observationId, :sensorId, :ts, :frequencyMhz, :bandwidthKhz, :powerDbm, :lat, :lon)
                ON CONFLICT DO NOTHING
                """, params);
    }

    public AlertDetails.Lifecycle findLifecycle(String alertId) {
        return jdbc.queryForObject(
                "SELECT acknowledged_at, resolved_at, resolved_reason FROM alerts WHERE alert_id = :alertId",
                new MapSqlParameterSource("alertId", alertId),
                (rs, row) -> new AlertDetails.Lifecycle(instant(rs, "acknowledged_at"), instant(rs, "resolved_at"),
                        rs.getString("resolved_reason")));
    }

    public List<ObservationModel> latestObservations(String alertId, int limit) {
        return jdbc.query("""
                SELECT observation_id, sensor_id, ts, frequency_mhz, bandwidth_khz, power_dbm, lat, lon
                FROM alert_observations WHERE alert_id = :alertId ORDER BY ts DESC LIMIT :limit
                """, new MapSqlParameterSource("alertId", alertId).addValue("limit", limit),
                (rs, row) -> new ObservationModel(rs.getLong("observation_id"), rs.getString("sensor_id"),
                        instant(rs, "ts"), rs.getDouble("frequency_mhz"), rs.getDouble("bandwidth_khz"),
                        rs.getDouble("power_dbm"), new LocationModel(rs.getDouble("lat"), rs.getDouble("lon"))));
    }

    public int countObservations(String alertId) {
        return jdbc.queryForObject("SELECT count(*) FROM alert_observations WHERE alert_id = :alertId",
                new MapSqlParameterSource("alertId", alertId), Integer.class);
    }

    /** OPEN -> ACKNOWLEDGED. @return false if the alert was not OPEN */
    public boolean acknowledge(String alertId, Instant now) {
        return jdbc.update("UPDATE alerts SET state = 'ACKNOWLEDGED', acknowledged_at = :now, updated_at = now()"
                + " WHERE alert_id = :alertId AND state = 'OPEN'",
                new MapSqlParameterSource("alertId", alertId).addValue("now", utc(now))) == 1;
    }

    /** OPEN/ACKNOWLEDGED -> RESOLVED. @return false if the alert was already resolved (or does not exist) */
    public boolean resolve(String alertId, String reason, Instant now) {
        return jdbc.update("UPDATE alerts SET state = 'RESOLVED', resolved_reason = :reason, resolved_at = :now,"
                + " updated_at = now() WHERE alert_id = :alertId AND state <> 'RESOLVED'",
                new MapSqlParameterSource("alertId", alertId).addValue("reason", reason)
                        .addValue("now", utc(now))) == 1;
    }

    /**
     * Auto-resolves every active alert whose last match (event time) is older than resolve_after_s at
     * {@code cutoff}. Runs as one statement, so it cannot interleave with an event holding the row lock.
     *
     * @return severities of the resolved alerts, for metrics
     */
    public List<Severity> resolveExpired(Instant cutoff, Instant now) {
        return jdbc.query("""
                UPDATE alerts SET state = 'RESOLVED', resolved_reason = 'AUTO', resolved_at = :now, updated_at = now()
                WHERE state <> 'RESOLVED' AND last_seen + make_interval(secs => resolve_after_s) < :cutoff
                RETURNING severity
                """, new MapSqlParameterSource("cutoff", utc(cutoff)).addValue("now", utc(now)),
                (rs, row) -> Severity.valueOf(rs.getString("severity")));
    }

    /** All filters are optional (null = not applied). Most recently active first. */
    public List<AlertModel> find(AlertState state, Severity severity, String sensorId, Double minMhz, Double maxMhz,
            int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM alerts WHERE TRUE");
        MapSqlParameterSource params = new MapSqlParameterSource("limit", limit);
        if (state != null) {
            sql.append(" AND state = :state");
            params.addValue("state", state.name());
        }
        if (severity != null) {
            sql.append(" AND severity = :severity");
            params.addValue("severity", severity.name());
        }
        if (sensorId != null) {
            sql.append(" AND sensor_ids @> ARRAY[CAST(:sensorId AS text)]");
            params.addValue("sensorId", sensorId);
        }
        if (minMhz != null) {
            sql.append(" AND frequency_mhz >= :minMhz");
            params.addValue("minMhz", minMhz);
        }
        if (maxMhz != null) {
            sql.append(" AND frequency_mhz <= :maxMhz");
            params.addValue("maxMhz", maxMhz);
        }
        sql.append(" ORDER BY last_seen DESC, alert_id LIMIT :limit");
        return jdbc.query(sql.toString(), params, this::map);
    }

    private MapSqlParameterSource params(AlertModel alert) {
        try {
            return new MapSqlParameterSource()
                    .addValue("alertId", alert.alertId())
                    .addValue("ruleId", alert.ruleId())
                    .addValue("state", alert.state().name())
                    .addValue("severity", alert.severity().name())
                    .addValue("frequencyMhz", alert.frequencyMhz())
                    .addValue("sensorIds", objectMapper.writeValueAsString(alert.sensorIds()))
                    .addValue("firstSeen", utc(alert.firstSeen()))
                    .addValue("lastSeen", utc(alert.lastSeen()))
                    .addValue("occurrences", alert.occurrences())
                    .addValue("peakPowerDbm", alert.peakPowerDbm());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private AlertModel map(ResultSet rs, int row) throws SQLException {
        return new AlertModel(
                rs.getString("alert_id"),
                rs.getString("rule_id"),
                AlertState.valueOf(rs.getString("state")),
                Severity.valueOf(rs.getString("severity")),
                rs.getDouble("frequency_mhz"),
                Arrays.asList((String[]) rs.getArray("sensor_ids").getArray()),
                instant(rs, "first_seen"),
                instant(rs, "last_seen"),
                rs.getInt("occurrences"),
                rs.getDouble("peak_power_dbm"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
