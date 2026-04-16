package com.inboxintelligence.ingester.outbound;

import com.inboxintelligence.ingester.config.EmailEventPublishProperties;
import com.inboxintelligence.ingester.model.EmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class EmailEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final EmailEventPublishProperties queueProperties;

    public void publishEmailProcessed(Long emailContentId) {

        var event = new EmailEvent(emailContentId);
        rabbitTemplate.convertAndSend(queueProperties.exchange(), queueProperties.routingKey(), event);
        log.info("Published email processed event for emailContentId: {}", emailContentId);
    }
}
