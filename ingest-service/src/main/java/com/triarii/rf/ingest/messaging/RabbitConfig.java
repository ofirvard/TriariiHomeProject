package com.triarii.rf.ingest.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.triarii.rf.common.Messaging;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the full topology (idempotent), so whichever service starts first creates it and no event is
 * dropped as unroutable before alert-service is up. alert-service declares the same topology.
 */
@Configuration
public class RabbitConfig {

    @Bean
    TopicExchange rfEventsExchange() {
        return new TopicExchange(Messaging.EXCHANGE, true, false);
    }

    @Bean
    FanoutExchange deadLetterExchange() {
        return new FanoutExchange(Messaging.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue ruleMatchQueue() {
        return QueueBuilder.durable(Messaging.RULE_MATCH_QUEUE)
                .deadLetterExchange(Messaging.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    Queue ruleMatchDeadLetterQueue() {
        return QueueBuilder.durable(Messaging.RULE_MATCH_DLQ).build();
    }

    @Bean
    Binding ruleMatchBinding() {
        return BindingBuilder.bind(ruleMatchQueue()).to(rfEventsExchange()).with(Messaging.RULE_MATCH_ROUTING_KEY);
    }

    @Bean
    Binding deadLetterBinding() {
        return BindingBuilder.bind(ruleMatchDeadLetterQueue()).to(deadLetterExchange());
    }

    /** JSON with the same snake_case naming as the REST API. */
    @Bean
    MessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
