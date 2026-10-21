package com.triarii.rf.common;

import java.util.List;

/**
 * Published by ingest-service once a rule's condition holds (e.g. power above threshold for min_duration_s),
 * and again for every further matching observation. alert-service owns dedup and the alert lifecycle.
 *
 * @param signalKey     dedup key: same rule + same signal map to the same open alert
 * @param resolveAfterS auto-resolve timeout, carried so alert-service does not need the rules table
 * @param observations  contributing observations; the first event of a window carries the whole window
 */
public record RuleMatchEvent(
        String ruleId,
        String signalKey,
        Severity severity,
        long resolveAfterS,
        List<ObservationModel> observations) {
}
