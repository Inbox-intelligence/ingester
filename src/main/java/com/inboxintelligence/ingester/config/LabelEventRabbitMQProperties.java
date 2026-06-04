package com.inboxintelligence.ingester.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.rabbitmq.label-event")
public record LabelEventRabbitMQProperties(
        String exchange,
        String applyQueue,
        String applyRoutingKey
) {
}
