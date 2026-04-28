package com.inboxintelligence.ingester.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.rabbitmq.cluster-event")
public record ClusterEventRabbitMQProperties(
        String exchange,
        String completedQueue,
        String completedRoutingKey,
        String labelingRoutingKey
) {
}
