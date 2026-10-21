package com.triarii.rf.ingest.rule;

import com.triarii.rf.common.Severity;

/**
 * A detection rule, in the same JSON shape as the API and the rules file. Type-specific fields are null when
 * they do not apply; {@link RuleValidator} enforces which ones are required per type.
 */
public record Rule(
        String ruleId,
        String name,
        String type,
        Band band,
        Severity severity,
        Integer resolveAfterS,
        Boolean enabled,
        // power_threshold
        Double thresholdDbm,
        Integer minDurationS,
        // multi_sensor
        Double toleranceKhz,
        Integer minSensors,
        Integer windowS) {

    public static final String POWER_THRESHOLD = "power_threshold";
    public static final String MULTI_SENSOR = "multi_sensor";

    public record Band(Double minMhz, Double maxMhz) {

        public boolean contains(double frequencyMhz) {
            return frequencyMhz >= minMhz && frequencyMhz <= maxMhz;
        }
    }

    Rule withRuleId(String id) {
        return new Rule(id, name, type, band, severity, resolveAfterS, enabled, thresholdDbm, minDurationS,
                toleranceKhz, minSensors, windowS);
    }

    /** Rules are enabled unless explicitly disabled. */
    Rule withDefaults() {
        return new Rule(ruleId, name, type, band, severity, resolveAfterS, enabled == null || enabled,
                thresholdDbm, minDurationS, toleranceKhz, minSensors, windowS);
    }
}
