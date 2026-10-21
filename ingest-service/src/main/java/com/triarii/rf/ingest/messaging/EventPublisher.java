package com.triarii.rf.ingest.messaging;

import com.triarii.rf.common.Messaging;
import com.triarii.rf.common.RuleMatchEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes rule matches. Delivery is at-least-once from here on (durable queue, consumer acks); a failure
 * to publish is logged and counted but the event is lost - closing that gap needs a transactional outbox
 * (see DECISIONS.md).
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate rabbit;
    private final Counter published;
    private final Counter failures;

    public EventPublisher(RabbitTemplate rabbit, MeterRegistry meters) {
        this.rabbit = rabbit;
        this.published = meters.counter("rf.events.published");
        this.failures = meters.counter("rf.events.publish.failures");
    }

    public void publish(RuleMatchEvent event) {
        try {
            rabbit.convertAndSend(Messaging.EXCHANGE, Messaging.RULE_MATCH_ROUTING_KEY, event);
            published.increment();
        } catch (AmqpException e) {
            failures.increment();
            log.error("Failed to publish event {} for rule {}", event.eventId(), event.ruleId(), e);
        }
    }
}
