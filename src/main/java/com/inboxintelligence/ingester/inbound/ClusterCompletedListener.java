package com.inboxintelligence.ingester.inbound;

import com.inboxintelligence.ingester.domain.GmailLabelSyncService;
import com.inboxintelligence.ingester.model.ClusterEvent;
import com.inboxintelligence.ingester.outbound.ClusterLabelingPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClusterCompletedListener {

    private final GmailLabelSyncService gmailLabelSyncService;
    private final ClusterLabelingPublisher clusterLabelingPublisher;

    @RabbitListener(queues = "#{@clusterCompletedQueue.name}")
    public void handleClusterCompleted(ClusterEvent event) {

        if (event.mailboxId() == null || event.mailboxId() <= 0) {
            log.error("Invalid mailboxId in cluster.completed event: {}", event.mailboxId());
            throw new IllegalArgumentException("mailboxId must be a positive integer");
        }

        log.info("Received cluster.completed for mailboxId [{}]", event.mailboxId());

        gmailLabelSyncService.syncForMailbox(event.mailboxId());
        clusterLabelingPublisher.publish(event.mailboxId());
    }
}
