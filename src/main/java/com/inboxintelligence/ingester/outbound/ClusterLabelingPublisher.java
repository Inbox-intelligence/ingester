package com.inboxintelligence.ingester.outbound;

import com.inboxintelligence.ingester.config.ClusterEventRabbitMQProperties;
import com.inboxintelligence.ingester.model.ClusterEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClusterLabelingPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ClusterEventRabbitMQProperties properties;

    public void publish(Long mailboxId) {
        ClusterEvent event = new ClusterEvent(mailboxId);
        rabbitTemplate.convertAndSend(properties.exchange(), properties.labelingRoutingKey(), event);
        log.info("Published cluster.labeling event for mailbox [{}]", mailboxId);
    }
}
