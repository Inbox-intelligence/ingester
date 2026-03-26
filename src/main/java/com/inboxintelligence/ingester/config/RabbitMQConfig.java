package com.inboxintelligence.ingester.config;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RabbitMQConfig {

    private final RabbitMQProperties properties;

    @Bean
    public Queue emailOutboundQueue() {
        return new Queue(properties.queue(), true);
    }

    @Bean
    public TopicExchange emailOutboundExchange() {
        return new TopicExchange(properties.exchange());
    }

    @Bean
    public Binding emailOutboundBinding(Queue emailOutboundQueue, TopicExchange emailOutboundExchange) {
        return BindingBuilder.bind(emailOutboundQueue)
                .to(emailOutboundExchange)
                .with(properties.routingKey());
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
