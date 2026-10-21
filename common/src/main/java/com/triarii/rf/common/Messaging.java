package com.triarii.rf.common;

/** RabbitMQ topology shared by the producer (ingest-service) and the consumer (alert-service). */
public final class Messaging {

    public static final String EXCHANGE = "rf.events";
    public static final String RULE_MATCH_ROUTING_KEY = "rule.match";
    public static final String RULE_MATCH_QUEUE = "alerts.rule-matches";
    public static final String DEAD_LETTER_EXCHANGE = "rf.events.dlx";
    public static final String RULE_MATCH_DLQ = "alerts.rule-matches.dlq";

    private Messaging() {
    }
}
