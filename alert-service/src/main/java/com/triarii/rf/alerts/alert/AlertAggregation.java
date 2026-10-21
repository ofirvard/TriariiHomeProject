package com.triarii.rf.alerts.alert;

import com.triarii.rf.common.ObservationModel;
import com.triarii.rf.common.RuleMatchEvent;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * How observations fold into an alert. Order-independent (min / max / sum / union), so events that arrive out
 * of order or are redelivered after a retry give the same result.
 */
final class AlertAggregation {

    private AlertAggregation() {
    }

    /** A new OPEN alert from the first event of a signal. */
    static AlertModel open(String alertId, RuleMatchEvent event) {
        List<ObservationModel> obs = event.observations();
        ObservationModel earliest = obs.stream().min(Comparator.comparing(ObservationModel::timestamp))
                .orElseThrow();
        return new AlertModel(alertId, event.ruleId(), AlertState.OPEN, event.severity(), earliest.frequencyMhz(),
                sensors(List.of(), obs), firstSeen(obs), lastSeen(obs), obs.size(), peak(obs));
    }

    /** Folds more observations into an alert. The state is untouched: acknowledged alerts keep updating. */
    static AlertModel merge(AlertModel alert, List<ObservationModel> obs) {
        Instant first = firstSeen(obs).isBefore(alert.firstSeen()) ? firstSeen(obs) : alert.firstSeen();
        Instant last = lastSeen(obs).isAfter(alert.lastSeen()) ? lastSeen(obs) : alert.lastSeen();
        return new AlertModel(alert.alertId(), alert.ruleId(), alert.state(), alert.severity(), alert.frequencyMhz(),
                sensors(alert.sensorIds(), obs), first, last, alert.occurrences() + obs.size(),
                Math.max(alert.peakPowerDbm(), peak(obs)));
    }

    static Instant firstSeen(List<ObservationModel> obs) {
        return obs.stream().map(ObservationModel::timestamp).min(Comparator.naturalOrder()).orElseThrow();
    }

    private static Instant lastSeen(List<ObservationModel> obs) {
        return obs.stream().map(ObservationModel::timestamp).max(Comparator.naturalOrder()).orElseThrow();
    }

    private static double peak(List<ObservationModel> obs) {
        return obs.stream().mapToDouble(ObservationModel::powerDbm).max().orElseThrow();
    }

    private static List<String> sensors(List<String> existing, List<ObservationModel> obs) {
        TreeSet<String> merged = new TreeSet<>(existing);
        obs.forEach(o -> merged.add(o.sensorId()));
        return List.copyOf(merged);
    }
}
