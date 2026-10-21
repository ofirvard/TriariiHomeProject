package com.triarii.rf.alerts.alert;

import com.triarii.rf.common.Severity;
import java.time.Instant;
import java.util.List;

/** The alert, as defined by the spec. first_seen / last_seen are event time. */
public record AlertModel(
        String alertId,
        String ruleId,
        AlertState state,
        Severity severity,
        double frequencyMhz,
        List<String> sensorIds,
        Instant firstSeen,
        Instant lastSeen,
        int occurrences,
        double peakPowerDbm) {
}
