package com.triarii.rf.ingest.rule;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Single validation path for rules from the API and from the rules file. */
@Component
public class RuleValidator {

    public List<String> validate(RuleModel rule) {
        List<String> errors = new ArrayList<>();
        if (rule.ruleId() == null || !rule.ruleId().matches("[A-Za-z0-9._-]{1,64}")) {
            errors.add("rule_id: required, 1-64 characters of [A-Za-z0-9._-]");
        }
        if (rule.name() == null || rule.name().isBlank()) {
            errors.add("name: required");
        }
        if (!RuleModel.POWER_THRESHOLD.equals(rule.type())) {
            errors.add("type: must be " + RuleModel.POWER_THRESHOLD);
        }
        if (rule.band() == null || rule.band().minMhz() == null || rule.band().maxMhz() == null
                || rule.band().minMhz() >= rule.band().maxMhz()) {
            errors.add("band: required, with min_mhz < max_mhz");
        }
        if (rule.thresholdDbm() == null) {
            errors.add("threshold_dbm: required");
        }
        if (rule.minDurationS() == null || rule.minDurationS() < 0) {
            errors.add("min_duration_s: required, must be >= 0");
        }
        if (rule.resolveAfterS() == null || rule.resolveAfterS() <= 0) {
            errors.add("resolve_after_s: required, must be > 0");
        }
        if (rule.severity() == null) {
            errors.add("severity: required (LOW, MEDIUM, HIGH, CRITICAL)");
        }
        return errors;
    }
}
