package com.inboxintelligence.ingester.outbound;

import com.inboxintelligence.ingester.config.RabbitMQProperties;
import com.inboxintelligence.ingester.model.EmailProcessedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class EmailEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMQProperties rabbitMQProperties;

    public void publishEmailProcessed(Long emailContentId) {

        var event = new EmailProcessedEvent(emailContentId);
        rabbitTemplate.convertAndSend(rabbitMQProperties.exchange(), rabbitMQProperties.routingKey(), event);
        log.info("Published email processed event for emailContentId: {}", emailContentId);
    }
}
