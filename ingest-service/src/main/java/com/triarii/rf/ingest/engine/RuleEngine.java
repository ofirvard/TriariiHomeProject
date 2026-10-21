package com.triarii.rf.ingest.engine;

import com.triarii.rf.common.ObservationModel;
import com.triarii.rf.common.RuleMatchEvent;
import com.triarii.rf.ingest.config.IngestProperties;
import com.triarii.rf.ingest.messaging.EventPublisher;
import com.triarii.rf.ingest.rule.RuleModel;
import com.triarii.rf.ingest.rule.RuleCache;
import com.triarii.rf.ingest.rule.RuleChangedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs stored observations through every enabled rule, in event-time order.
 *
 * <p>Late data: the watermark is the newest event time seen. Observations older than
 * (watermark - allowed_lateness) are stored but not evaluated, so a straggler cannot reopen an alert that has
 * already auto-resolved. Within the lateness window, out-of-order observations are evaluated normally.
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final RuleCache rules;
    private final Map<String, RuleEvaluator> evaluators;
    private final EventPublisher publisher;
    private final Duration allowedLateness;
    private final AtomicReference<Instant> watermark = new AtomicReference<>(Instant.EPOCH);
    private final Counter lateCounter;
    private final MeterRegistry meters;

    public RuleEngine(RuleCache rules, List<RuleEvaluator> evaluators, EventPublisher publisher,
            IngestProperties properties, MeterRegistry meters) {
        this.rules = rules;
        this.evaluators = evaluators.stream().collect(Collectors.toMap(RuleEvaluator::type, Function.identity()));
        this.publisher = publisher;
        this.allowedLateness = properties.allowedLateness();
        this.meters = meters;
        this.lateCounter = meters.counter("rf.observations.late");
    }

    /** Never throws: the observations are already committed, a failure here must not fail the request. */
    public void process(List<ObservationModel> observations) {
        try {
            List<RuleMatchEvent> events = new ArrayList<>();
            List<RuleModel> enabled = rules.enabled();
            observations.stream()
                    .sorted(Comparator.comparing(ObservationModel::timestamp))
                    .forEach(obs -> {
                        Instant wm = watermark.accumulateAndGet(obs.timestamp(),
                                (a, b) -> a.isAfter(b) ? a : b);
                        if (obs.timestamp().isBefore(wm.minus(allowedLateness))) {
                            lateCounter.increment();
                            return;
                        }
                        for (RuleModel rule : enabled) {
                            RuleEvaluator evaluator = evaluators.get(rule.type());
                            if (evaluator != null) {
                                events.addAll(evaluator.evaluate(rule, obs));
                            }
                        }
                    });
            for (RuleMatchEvent event : events) {
                meters.counter("rf.rule.matches", "rule_id", event.ruleId()).increment();
                publisher.publish(event);
            }
        } catch (RuntimeException e) {
            log.error("Rule evaluation failed for {} observations", observations.size(), e);
        }
    }

    public Instant watermark() {
        return watermark.get();
    }

    @EventListener
    void onRuleChanged(RuleChangedEvent event) {
        evaluators.values().forEach(evaluator -> evaluator.forget(event.ruleId()));
    }

    @Scheduled(fixedDelay = 30_000)
    void evictStaleState() {
        Instant horizon = watermark.get().minus(allowedLateness);
        evaluators.values().forEach(evaluator -> evaluator.evictBefore(horizon));
    }
}
