package com.triarii.rf.ingest.rule;

import com.triarii.rf.common.Severity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RuleRepository {

    private static final String COLUMNS = """
            rule_id, name, type, min_mhz, max_mhz, severity, enabled, resolve_after_s,
            threshold_dbm, min_duration_s, tolerance_khz, min_sensors, window_s
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RuleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RuleModel> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM rules ORDER BY rule_id", RuleRepository::map);
    }

    public Optional<RuleModel> findById(String ruleId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rules WHERE rule_id = :ruleId",
                new MapSqlParameterSource("ruleId", ruleId), RuleRepository::map).stream().findFirst();
    }

    /** @return false if a rule with this id already exists */
    public boolean insert(RuleModel rule) {
        return jdbc.update("""
                INSERT INTO rules (rule_id, name, type, min_mhz, max_mhz, severity, enabled, resolve_after_s,
                                   threshold_dbm, min_duration_s, tolerance_khz, min_sensors, window_s)
                VALUES (:ruleId, :name, :type, :minMhz, :maxMhz, :severity, :enabled, :resolveAfterS,
                        :thresholdDbm, :minDurationS, :toleranceKhz, :minSensors, :windowS)
                ON CONFLICT (rule_id) DO NOTHING
                """, params(rule)) == 1;
    }

    /** @return false if no rule with this id exists */
    public boolean update(RuleModel rule) {
        return jdbc.update("""
                UPDATE rules SET name = :name, type = :type, min_mhz = :minMhz, max_mhz = :maxMhz,
                    severity = :severity, enabled = :enabled, resolve_after_s = :resolveAfterS,
                    threshold_dbm = :thresholdDbm, min_duration_s = :minDurationS,
                    tolerance_khz = :toleranceKhz, min_sensors = :minSensors, window_s = :windowS,
                    updated_at = now()
                WHERE rule_id = :ruleId
                """, params(rule)) == 1;
    }

    /** @return false if no rule with this id exists */
    public boolean delete(String ruleId) {
        return jdbc.update("DELETE FROM rules WHERE rule_id = :ruleId",
                new MapSqlParameterSource("ruleId", ruleId)) == 1;
    }

    private static MapSqlParameterSource params(RuleModel rule) {
        return new MapSqlParameterSource()
                .addValue("ruleId", rule.ruleId())
                .addValue("name", rule.name())
                .addValue("type", rule.type())
                .addValue("minMhz", rule.band().minMhz())
                .addValue("maxMhz", rule.band().maxMhz())
                .addValue("severity", rule.severity().name())
                .addValue("enabled", rule.enabled())
                .addValue("resolveAfterS", rule.resolveAfterS())
                .addValue("thresholdDbm", rule.thresholdDbm())
                .addValue("minDurationS", rule.minDurationS())
                .addValue("toleranceKhz", rule.toleranceKhz())
                .addValue("minSensors", rule.minSensors())
                .addValue("windowS", rule.windowS());
    }

    private static RuleModel map(ResultSet rs, int row) throws SQLException {
        return new RuleModel(
                rs.getString("rule_id"),
                rs.getString("name"),
                rs.getString("type"),
                new RuleModel.Band(rs.getDouble("min_mhz"), rs.getDouble("max_mhz")),
                Severity.valueOf(rs.getString("severity")),
                rs.getInt("resolve_after_s"),
                rs.getBoolean("enabled"),
                rs.getObject("threshold_dbm", Double.class),
                rs.getObject("min_duration_s", Integer.class),
                rs.getObject("tolerance_khz", Double.class),
                rs.getObject("min_sensors", Integer.class),
                rs.getObject("window_s", Integer.class));
    }
}
