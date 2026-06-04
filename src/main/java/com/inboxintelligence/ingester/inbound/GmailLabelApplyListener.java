package com.inboxintelligence.ingester.inbound;

import com.inboxintelligence.ingester.domain.label.GmailLabelPublishService;
import com.inboxintelligence.ingester.model.GmailLabelApplyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class GmailLabelApplyListener {

    private final GmailLabelPublishService gmailLabelPublishService;

    @RabbitListener(queues = "#{@gmailLabelApplyQueue.name}")
    public void handleGmailLabelApplyEvent(GmailLabelApplyEvent event) {

        log.debug("Received gmail-label apply event [mailboxAddress={}, gmailLabelId={}, count={}]", event.mailboxAddress(), event.gmailLabelId(), event.gmailMessageIds().size());

        gmailLabelPublishService.applyLabelsToMessages(
                event.mailboxAddress(),
                event.gmailMessageIds(),
                List.of(event.gmailLabelId()),
                List.of());
    }
}
