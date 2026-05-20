package com.inboxintelligence.ingester.config;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RabbitMQConfig {

    private final EmailEventPublishProperties emailEventProperties;

    // --- Email-event exchange (outbound only) ---

    @Bean
    public TopicExchange emailEventExchange() {
        return new TopicExchange(emailEventProperties.exchange());
    }

    // --- Message converter ---

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
