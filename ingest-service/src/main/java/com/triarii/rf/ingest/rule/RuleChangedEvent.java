package com.triarii.rf.ingest.rule;

/** Published (in-process) after a rule is created, updated or deleted. */
public record RuleChangedEvent(String ruleId) {
}
