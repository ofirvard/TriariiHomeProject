package com.triarii.rf.ingest.engine;

import com.triarii.rf.common.Observation;
import com.triarii.rf.common.RuleMatchEvent;
import com.triarii.rf.ingest.config.IngestProperties;
import com.triarii.rf.ingest.rule.Rule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * power_threshold: an observation inside the band with power above threshold_dbm, persisting for at least
 * min_duration_s (event time) before anything is published.
 *
 * <p>State is one window per signal, i.e. (rule, frequency bucket) - the same key alert-service dedups on. Any
 * sensor reporting the signal feeds the window, so once a signal is alerting, a match from a sensor that only
 * just started seeing it updates the alert immediately:
 * <ul>
 *   <li>pending: matches are buffered; a gap larger than max_gap starts a new window, so a short burst (or two
 *       bursts far apart) never adds up to min_duration_s;</li>
 *   <li>firing: once last - first >= min_duration_s the whole buffer is published as one event, then every
 *       further match is published on its own. A firing window only ends after resolve_after_s without a
 *       match, i.e. exactly when alert-service auto-resolves the alert.</li>
 * </ul>
 * Windows track min(first) / max(last), so out-of-order observations within a window are handled; an
 * observation older than the window by more than the gap is ignored.
 */
@Component
public class PowerThresholdEvaluator implements RuleEvaluator {

    private final Duration maxGap;
    private final double bucketMhz;
    private final int maxPending;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public PowerThresholdEvaluator(IngestProperties properties) {
        this.maxGap = properties.maxGap();
        this.bucketMhz = properties.bucketMhz();
        this.maxPending = properties.maxPendingObservations();
    }

    @Override
    public String type() {
        return Rule.POWER_THRESHOLD;
    }

    @Override
    public List<RuleMatchEvent> evaluate(Rule rule, Observation obs) {
        if (!rule.band().contains(obs.frequencyMhz()) || obs.powerDbm() <= rule.thresholdDbm()) {
            return List.of();
        }

        String bucket = bucket(obs.frequencyMhz());
        String key = rule.ruleId() + "|" + bucket;
        Duration minDuration = Duration.ofSeconds(rule.minDurationS());
        List<RuleMatchEvent> events = new ArrayList<>(1);

        windows.compute(key, (k, window) -> {
            if (window == null || obs.timestamp().isAfter(window.last.plus(allowedGap(window)))) {
                window = new Window(obs.timestamp(), Duration.ofSeconds(rule.resolveAfterS()));
            } else if (obs.timestamp().isBefore(window.first.minus(allowedGap(window)))) {
                return window; // far older than the current window: stored, but not part of it
            }
            window.add(obs, maxPending);

            if (window.firing) {
                events.add(event(rule, bucket, List.of(obs)));
            } else if (Duration.between(window.first, window.last).compareTo(minDuration) >= 0) {
                window.firing = true;
                events.add(event(rule, bucket, window.drainPending()));
            }
            return window;
        });
        return events;
    }

    @Override
    public void forget(String ruleId) {
        windows.keySet().removeIf(key -> key.startsWith(ruleId + "|"));
    }

    @Override
    public void evictBefore(Instant horizon) {
        for (String key : windows.keySet()) {
            windows.computeIfPresent(key,
                    (k, w) -> w.last.plus(allowedGap(w)).isBefore(horizon) ? null : w);
        }
    }

    int windowCount() {
        return windows.size();
    }

    private Duration allowedGap(Window window) {
        return window.firing ? window.resolveAfter : maxGap;
    }

    private String bucket(double frequencyMhz) {
        return String.format(Locale.ROOT, "%.3f", Math.round(frequencyMhz / bucketMhz) * bucketMhz);
    }

    private static RuleMatchEvent event(Rule rule, String bucket, List<Observation> observations) {
        return new RuleMatchEvent(UUID.randomUUID().toString(), rule.ruleId(), rule.type(),
                rule.ruleId() + "@" + bucket, rule.severity(), rule.resolveAfterS(), observations);
    }

    /** Mutated only inside ConcurrentHashMap.compute, which serializes access per key. */
    private static final class Window {

        final Duration resolveAfter;
        Instant first;
        Instant last;
        boolean firing;
        List<Observation> pending = new ArrayList<>();

        Window(Instant start, Duration resolveAfter) {
            this.resolveAfter = resolveAfter;
            this.first = start;
            this.last = start;
        }

        void add(Observation obs, int maxPending) {
            if (obs.timestamp().isBefore(first)) {
                first = obs.timestamp();
            }
            if (obs.timestamp().isAfter(last)) {
                last = obs.timestamp();
            }
            if (!firing && pending.size() < maxPending) {
                pending.add(obs);
            }
        }

        List<Observation> drainPending() {
            List<Observation> drained = pending;
            drained.sort(Comparator.comparing(Observation::timestamp));
            pending = List.of();
            return drained;
        }
    }
}
