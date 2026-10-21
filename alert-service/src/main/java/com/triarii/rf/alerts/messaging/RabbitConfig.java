package com.triarii.rf.alerts.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.triarii.rf.common.Messaging;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Same durable queue as ingest-service (declaring it is idempotent), so either service can start first. */
@Configuration
public class RabbitConfig {

    @Bean
    Queue ruleMatchQueue() {
        return new Queue(Messaging.RULE_MATCH_QUEUE, true);
    }

    /** snake_case JSON; only the shared contract package may be instantiated from message type headers. */
    @Bean
    MessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper, "com.triarii.rf.common");
    }
}
