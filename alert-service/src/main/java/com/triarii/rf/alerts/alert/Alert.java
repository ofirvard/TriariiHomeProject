package com.triarii.rf.alerts.alert;

import com.triarii.rf.common.Observation;
import com.triarii.rf.common.RuleMatchEvent;
import com.triarii.rf.common.Severity;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** An alert. first_seen / last_seen are event time; the *_at fields are wall-clock audit times. */
public record Alert(
        String alertId,
        String ruleId,
        String signalKey,
        AlertState state,
        Severity severity,
        double frequencyMhz,
        List<String> sensorIds,
        Instant firstSeen,
        Instant lastSeen,
        int occurrences,
        double peakPowerDbm,
        int resolveAfterS,
        Instant acknowledgedAt,
        Instant resolvedAt,
        String resolvedReason) {

    /** A new OPEN alert from the first event of a signal. */
    static Alert open(String alertId, RuleMatchEvent event) {
        List<Observation> obs = event.observations();
        Observation earliest = obs.stream().min(Comparator.comparing(Observation::timestamp))
                .orElseThrow();
        return new Alert(alertId, event.ruleId(), event.signalKey(), AlertState.OPEN, event.severity(),
                earliest.frequencyMhz(), sensors(List.of(), obs), firstSeen(obs), lastSeen(obs), obs.size(),
                peak(obs), (int) event.resolveAfterS(), null, null, null);
    }

    /**
     * Folds more observations into this alert. Order-independent (min / max / union), so events that arrive
     * out of order or after a retry give the same result. The state is untouched: acknowledged alerts keep
     * updating.
     */
    Alert merge(List<Observation> obs) {
        Instant first = firstSeen(obs).isBefore(firstSeen) ? firstSeen(obs) : firstSeen;
        Instant last = lastSeen(obs).isAfter(lastSeen) ? lastSeen(obs) : lastSeen;
        return new Alert(alertId, ruleId, signalKey, state, severity, frequencyMhz, sensors(sensorIds, obs),
                first, last, occurrences + obs.size(), Math.max(peakPowerDbm, peak(obs)), resolveAfterS,
                acknowledgedAt, resolvedAt, resolvedReason);
    }

    /** True if the observations start after this alert should already have auto-resolved. */
    boolean expiredBefore(Instant eventTime) {
        return eventTime.isAfter(lastSeen.plusSeconds(resolveAfterS));
    }

    static Instant firstSeen(List<Observation> obs) {
        return obs.stream().map(Observation::timestamp).min(Comparator.naturalOrder()).orElseThrow();
    }

    private static Instant lastSeen(List<Observation> obs) {
        return obs.stream().map(Observation::timestamp).max(Comparator.naturalOrder()).orElseThrow();
    }

    private static double peak(List<Observation> obs) {
        return obs.stream().mapToDouble(Observation::powerDbm).max().orElseThrow();
    }

    private static List<String> sensors(List<String> existing, List<Observation> obs) {
        TreeSet<String> merged = new TreeSet<>(existing);
        obs.forEach(o -> merged.add(o.sensorId()));
        return List.copyOf(merged);
    }
}
