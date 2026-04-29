package com.inboxintelligence.ingester.outbound;

import com.inboxintelligence.ingester.config.EmailEventPublishProperties;
import com.inboxintelligence.ingester.model.EmailEvent;
import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.service.EmailContentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import static com.inboxintelligence.persistence.model.ProcessedStatus.PUBLISHED_FOR_SANITIZATION;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final EmailEventPublishProperties queueProperties;
    private final EmailContentService emailContentService;

    public void publishEmailProcessed(EmailContent emailContent) {

        emailContentService.updateStatusAndNote(emailContent, PUBLISHED_FOR_SANITIZATION, null);
        EmailEvent event = new EmailEvent(emailContent.getId());
        rabbitTemplate.convertAndSend(queueProperties.exchange(), queueProperties.routingKey(), event);
        log.info("Published email processed event: {}", event);
    }
}
