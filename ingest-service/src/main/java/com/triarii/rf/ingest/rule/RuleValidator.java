package com.triarii.rf.ingest.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Single validation path for rules from the API and from the rules file. */
@Component
public class RuleValidator {

    private static final Pattern RULE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public List<String> validate(Rule rule) {
        List<String> errors = new ArrayList<>();

        if (rule.ruleId() == null || !RULE_ID.matcher(rule.ruleId()).matches()) {
            errors.add("rule_id: required, 1-64 characters of [A-Za-z0-9._-]");
        }
        if (rule.name() == null || rule.name().isBlank()) {
            errors.add("name: required");
        }
        if (rule.severity() == null) {
            errors.add("severity: required (LOW, MEDIUM, HIGH, CRITICAL)");
        }
        if (rule.resolveAfterS() == null || rule.resolveAfterS() <= 0) {
            errors.add("resolve_after_s: required, must be > 0");
        }
        validateBand(rule.band(), errors);

        if (Rule.POWER_THRESHOLD.equals(rule.type())) {
            if (rule.thresholdDbm() == null) {
                errors.add("threshold_dbm: required for power_threshold");
            }
            if (rule.minDurationS() == null || rule.minDurationS() < 0) {
                errors.add("min_duration_s: required for power_threshold, must be >= 0");
            }
        } else if (Rule.MULTI_SENSOR.equals(rule.type())) {
            if (rule.toleranceKhz() == null || rule.toleranceKhz() <= 0) {
                errors.add("tolerance_khz: required for multi_sensor, must be > 0");
            }
            if (rule.minSensors() == null || rule.minSensors() < 2) {
                errors.add("min_sensors: required for multi_sensor, must be >= 2");
            }
            if (rule.windowS() == null || rule.windowS() <= 0) {
                errors.add("window_s: required for multi_sensor, must be > 0");
            }
        } else {
            errors.add("type: must be one of " + Rule.POWER_THRESHOLD + ", " + Rule.MULTI_SENSOR);
        }
        return errors;
    }

    private static void validateBand(Rule.Band band, List<String> errors) {
        if (band == null || band.minMhz() == null || band.maxMhz() == null) {
            errors.add("band: required, with min_mhz and max_mhz");
        } else if (band.minMhz() >= band.maxMhz()) {
            errors.add("band: min_mhz must be less than max_mhz");
        } else if (band.minMhz() < 1 || band.maxMhz() > 6000) {
            errors.add("band: must be within 1-6000 MHz");
        }
    }
}
