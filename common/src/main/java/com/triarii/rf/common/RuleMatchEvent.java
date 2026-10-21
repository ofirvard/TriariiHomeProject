package com.triarii.rf.common;

import java.util.List;

/**
 * Published by ingest-service once a rule's condition holds (e.g. power above threshold for min_duration_s),
 * and again for every further matching observation. alert-service owns dedup and the alert lifecycle.
 *
 * @param eventId       idempotency key; delivery is at-least-once
 * @param signalKey     dedup key: same rule + same signal map to the same open alert
 * @param resolveAfterS auto-resolve timeout, carried so alert-service does not need the rules table
 * @param observations  contributing observations; the first event of a window carries the whole pending window
 */
public record RuleMatchEvent(
        String eventId,
        String ruleId,
        String ruleType,
        String signalKey,
        Severity severity,
        long resolveAfterS,
        List<Observation> observations) {
}
