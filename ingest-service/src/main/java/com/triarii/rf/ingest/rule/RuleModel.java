package com.triarii.rf.ingest.rule;

import com.triarii.rf.common.Severity;

/** A detection rule, in the same JSON shape as the API and the rules file. */
public record RuleModel(
        String ruleId,
        String name,
        String type,
        Band band,
        Double thresholdDbm,
        Integer minDurationS,
        Integer resolveAfterS,
        Severity severity,
        Boolean enabled) {

    public static final String POWER_THRESHOLD = "power_threshold";

    public record Band(Double minMhz, Double maxMhz) {

        public boolean contains(double frequencyMhz) {
            return frequencyMhz >= minMhz && frequencyMhz <= maxMhz;
        }
    }

    /** The id in the URL path wins over the body; rules are enabled unless explicitly disabled. */
    RuleModel normalized(String id) {
        return new RuleModel(id, name, type, band, thresholdDbm, minDurationS, resolveAfterS, severity,
                enabled == null || enabled);
    }
}
