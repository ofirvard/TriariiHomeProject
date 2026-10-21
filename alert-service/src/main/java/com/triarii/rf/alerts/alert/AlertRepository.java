package com.triarii.rf.alerts.alert;

import com.triarii.rf.common.LocationModel;
import com.triarii.rf.common.ObservationModel;
import com.triarii.rf.common.RuleMatchEvent;
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

    private static final String SELECT = """
            SELECT alert_id, rule_id, state, severity, frequency_mhz, sensor_ids, first_seen, last_seen,
                   occurrences, peak_power_dbm
            FROM alert_view WHERE TRUE
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AlertRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The OPEN/ACKNOWLEDGED alert for a signal, row-locked until the transaction ends. */
    public Optional<String> findActiveIdForUpdate(String signalKey) {
        return jdbc.queryForList("SELECT alert_id FROM alerts WHERE signal_key = :signalKey"
                        + " AND state <> 'RESOLVED' FOR UPDATE",
                new MapSqlParameterSource("signalKey", signalKey), String.class).stream().findFirst();
    }

    public void insert(String alertId, RuleMatchEvent event, double frequencyMhz) {
        jdbc.update("""
                INSERT INTO alerts (alert_id, rule_id, signal_key, state, severity, frequency_mhz, resolve_after_s)
                VALUES (:alertId, :ruleId, :signalKey, 'OPEN', :severity, :frequencyMhz, :resolveAfterS)
                """, new MapSqlParameterSource()
                .addValue("alertId", alertId)
                .addValue("ruleId", event.ruleId())
                .addValue("signalKey", event.signalKey())
                .addValue("severity", event.severity().name())
                .addValue("frequencyMhz", frequencyMhz)
                .addValue("resolveAfterS", event.resolveAfterS()));
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

    public Optional<AlertModel> findById(String alertId) {
        return jdbc.query(SELECT + " AND alert_id = :alertId", new MapSqlParameterSource("alertId", alertId),
                AlertRepository::map).stream().findFirst();
    }

    /** All filters are optional (null = not applied). Most recently active first. */
    public List<AlertModel> find(AlertState state, Severity severity, String sensorId, Double minMhz, Double maxMhz,
            int limit) {
        StringBuilder sql = new StringBuilder(SELECT);
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
            sql.append(" AND :sensorId = ANY(sensor_ids)");
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
        return jdbc.query(sql.toString(), params, AlertRepository::map);
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

    /** OPEN -> ACKNOWLEDGED. @return false if the alert was not OPEN */
    public boolean acknowledge(String alertId) {
        return jdbc.update("UPDATE alerts SET state = 'ACKNOWLEDGED' WHERE alert_id = :alertId AND state = 'OPEN'",
                new MapSqlParameterSource("alertId", alertId)) == 1;
    }

    /** OPEN/ACKNOWLEDGED -> RESOLVED. */
    public void resolve(String alertId) {
        jdbc.update("UPDATE alerts SET state = 'RESOLVED' WHERE alert_id = :alertId AND state <> 'RESOLVED'",
                new MapSqlParameterSource("alertId", alertId));
    }

    /**
     * Resolves every active alert whose last observation (event time) is older than resolve_after_s at
     * {@code cutoff}. One statement, so it cannot interleave with an event holding the row lock.
     */
    public int resolveExpired(Instant cutoff) {
        return jdbc.update("""
                UPDATE alerts a SET state = 'RESOLVED'
                WHERE a.state <> 'RESOLVED'
                  AND (SELECT max(o.ts) FROM alert_observations o WHERE o.alert_id = a.alert_id)
                      + make_interval(secs => a.resolve_after_s) < :cutoff
                """, new MapSqlParameterSource("cutoff", utc(cutoff)));
    }

    private static AlertModel map(ResultSet rs, int row) throws SQLException {
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
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
