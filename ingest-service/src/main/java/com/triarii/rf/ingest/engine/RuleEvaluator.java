package com.triarii.rf.ingest.engine;

import com.triarii.rf.common.ObservationModel;
import com.triarii.rf.common.RuleMatchEvent;
import com.triarii.rf.ingest.rule.RuleModel;
import java.time.Instant;
import java.util.List;

/** Evaluates one rule type. Implementations keep their own (event-time) state and must be thread-safe. */
public interface RuleEvaluator {

    String type();

    /** @return events to publish for this observation (usually none) */
    List<RuleMatchEvent> evaluate(RuleModel rule, ObservationModel observation);

    /** Drop all state for a rule (it was changed, disabled or deleted). */
    void forget(String ruleId);

    /** Drop state that can no longer change anything, given that nothing older than {@code horizon} is evaluated. */
    void evictBefore(Instant horizon);
}
