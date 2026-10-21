package com.triarii.rf.common;

/** The one durable queue between ingest-service (producer) and alert-service (consumer). */
public final class Messaging {

    public static final String RULE_MATCH_QUEUE = "alerts.rule-matches";

    private Messaging() {
    }
}
