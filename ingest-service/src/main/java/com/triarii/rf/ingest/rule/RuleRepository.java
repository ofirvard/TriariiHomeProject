package com.triarii.rf.ingest.rule;

import com.triarii.rf.common.Severity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RuleRepository {

    private static final String SELECT = """
            SELECT rule_id, name, type, min_mhz, max_mhz, threshold_dbm, min_duration_s, resolve_after_s,
                   severity, enabled
            FROM rules
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RuleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RuleModel> findAll() {
        return jdbc.query(SELECT + " ORDER BY rule_id", RuleRepository::map);
    }

    public List<RuleModel> findEnabled() {
        return jdbc.query(SELECT + " WHERE enabled", RuleRepository::map);
    }

    /** @return false if a rule with this id already exists */
    public boolean insert(RuleModel rule) {
        return jdbc.update("""
                INSERT INTO rules (rule_id, name, type, min_mhz, max_mhz, threshold_dbm, min_duration_s,
                                   resolve_after_s, severity, enabled)
                VALUES (:ruleId, :name, :type, :minMhz, :maxMhz, :thresholdDbm, :minDurationS,
                        :resolveAfterS, :severity, :enabled)
                ON CONFLICT (rule_id) DO NOTHING
                """, params(rule)) == 1;
    }

    /** @return false if no rule with this id exists */
    public boolean update(RuleModel rule) {
        return jdbc.update("""
                UPDATE rules SET name = :name, type = :type, min_mhz = :minMhz, max_mhz = :maxMhz,
                    threshold_dbm = :thresholdDbm, min_duration_s = :minDurationS,
                    resolve_after_s = :resolveAfterS, severity = :severity, enabled = :enabled
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
                .addValue("thresholdDbm", rule.thresholdDbm())
                .addValue("minDurationS", rule.minDurationS())
                .addValue("resolveAfterS", rule.resolveAfterS())
                .addValue("severity", rule.severity().name())
                .addValue("enabled", rule.enabled());
    }

    private static RuleModel map(ResultSet rs, int row) throws SQLException {
        return new RuleModel(
                rs.getString("rule_id"),
                rs.getString("name"),
                rs.getString("type"),
                new RuleModel.Band(rs.getDouble("min_mhz"), rs.getDouble("max_mhz")),
                rs.getDouble("threshold_dbm"),
                rs.getInt("min_duration_s"),
                rs.getInt("resolve_after_s"),
                Severity.valueOf(rs.getString("severity")),
                rs.getBoolean("enabled"));
    }
}
