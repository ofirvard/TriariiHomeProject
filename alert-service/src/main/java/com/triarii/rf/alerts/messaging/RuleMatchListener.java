package com.triarii.rf.alerts.messaging;

import com.triarii.rf.alerts.alert.AlertService;
import com.triarii.rf.common.Messaging;
import com.triarii.rf.common.RuleMatchEvent;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes rule matches. The message is acked only after the transaction commits; on failure it is retried
 * (see spring.rabbitmq.listener in application.yml) and then dead-lettered instead of blocking the queue.
 */
@Component
public class RuleMatchListener {

    private final AlertService alerts;

    public RuleMatchListener(AlertService alerts) {
        this.alerts = alerts;
    }

    @RabbitListener(queues = Messaging.RULE_MATCH_QUEUE)
    public void onRuleMatch(RuleMatchEvent event) {
        alerts.apply(event);
    }
}
