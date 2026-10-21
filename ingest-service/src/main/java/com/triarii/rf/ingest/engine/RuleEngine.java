package com.triarii.rf.ingest.engine;

import com.triarii.rf.common.Messaging;
import com.triarii.rf.common.ObservationModel;
import com.triarii.rf.common.RuleMatchEvent;
import com.triarii.rf.ingest.rule.RuleModel;
import com.triarii.rf.ingest.rule.RuleRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Evaluates power_threshold rules: an observation inside the band with power above threshold_dbm, persisting
 * for at least min_duration_s (event time) before anything is published.
 *
 * <p>State is one window per signal = (rule, 0.1 MHz frequency bucket), the same key alert-service dedups on:
 * <ul>
 *   <li>pending: matches are buffered; a gap larger than max_gap starts a new window, so a short burst (or two
 *       bursts far apart) never adds up to min_duration_s;</li>
 *   <li>firing: once last - first >= min_duration_s the buffer is published as one event, then every further
 *       match is published on its own. A firing window only ends after resolve_after_s without a match, i.e.
 *       when alert-service auto-resolves the alert.</li>
 * </ul>
 * Out-of-order observations: windows track min(first) / max(last), and each batch is evaluated in timestamp
 * order. An observation older than its window by more than the gap is ignored (it is still stored).
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);
    private static final double BUCKET_MHZ = 0.1;

    private final RuleRepository rules;
    private final RabbitTemplate rabbit;
    private final Duration maxGap;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RuleEngine(RuleRepository rules, RabbitTemplate rabbit, @Value("${rf.max-gap}") Duration maxGap) {
        this.rules = rules;
        this.rabbit = rabbit;
        this.maxGap = maxGap;
    }

    /** Runs stored observations through every enabled rule, in event-time order, and publishes the matches. */
    public void process(List<ObservationModel> observations) {
        List<RuleModel> enabled = rules.findEnabled();
        observations.stream()
                .sorted(Comparator.comparing(ObservationModel::timestamp))
                .forEach(obs -> enabled.forEach(rule -> evaluate(rule, obs).forEach(this::publish)));
    }

    /** Drops all windows of a rule (it was changed or deleted). */
    public void forget(String ruleId) {
        windows.keySet().removeIf(key -> key.startsWith(ruleId + "@"));
    }

    /** @return events to publish for this observation (usually none) */
    List<RuleMatchEvent> evaluate(RuleModel rule, ObservationModel obs) {
        if (!rule.band().contains(obs.frequencyMhz()) || obs.powerDbm() <= rule.thresholdDbm()) {
            return List.of();
        }
        String signalKey = rule.ruleId() + "@"
                + String.format(Locale.ROOT, "%.3f", Math.round(obs.frequencyMhz() / BUCKET_MHZ) * BUCKET_MHZ);
        Duration minDuration = Duration.ofSeconds(rule.minDurationS());
        Duration resolveAfter = Duration.ofSeconds(rule.resolveAfterS());
        List<RuleMatchEvent> events = new ArrayList<>(1);

        windows.compute(signalKey, (key, window) -> {
            Duration gap = window != null && window.firing ? resolveAfter : maxGap;
            if (window == null || obs.timestamp().isAfter(window.last.plus(gap))) {
                window = new Window(obs.timestamp());
            } else if (obs.timestamp().isBefore(window.first.minus(gap))) {
                return window; // far older than the current window: not part of it
            }
            window.add(obs);
            if (window.firing) {
                events.add(event(rule, signalKey, List.of(obs)));
            } else if (Duration.between(window.first, window.last).compareTo(minDuration) >= 0) {
                window.firing = true;
                window.pending.sort(Comparator.comparing(ObservationModel::timestamp));
                events.add(event(rule, signalKey, window.pending));
                window.pending = List.of();
            }
            return window;
        });
        return events;
    }

    private void publish(RuleMatchEvent event) {
        try {
            rabbit.convertAndSend(Messaging.RULE_MATCH_QUEUE, event);
        } catch (AmqpException e) {
            // The observations are already stored; losing this event is documented (no outbox).
            log.error("Failed to publish match for {}", event.signalKey(), e);
        }
    }

    private static RuleMatchEvent event(RuleModel rule, String signalKey, List<ObservationModel> observations) {
        return new RuleMatchEvent(rule.ruleId(), signalKey, rule.severity(), rule.resolveAfterS(), observations);
    }

    /** Mutated only inside ConcurrentHashMap.compute, which serializes access per signal. */
    private static final class Window {

        Instant first;
        Instant last;
        boolean firing;
        List<ObservationModel> pending = new ArrayList<>();

        Window(Instant start) {
            first = start;
            last = start;
        }

        void add(ObservationModel obs) {
            if (obs.timestamp().isBefore(first)) {
                first = obs.timestamp();
            }
            if (obs.timestamp().isAfter(last)) {
                last = obs.timestamp();
            }
            if (!firing) {
                pending.add(obs);
            }
        }
    }
}
