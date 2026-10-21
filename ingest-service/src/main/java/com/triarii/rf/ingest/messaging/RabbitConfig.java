package com.triarii.rf.ingest.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.triarii.rf.common.Messaging;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the durable queue (idempotent; alert-service does the same), so either service can start first. */
@Configuration
public class RabbitConfig {

    @Bean
    Queue ruleMatchQueue() {
        return new Queue(Messaging.RULE_MATCH_QUEUE, true);
    }

    /** JSON with the same snake_case naming as the REST API. */
    @Bean
    MessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
