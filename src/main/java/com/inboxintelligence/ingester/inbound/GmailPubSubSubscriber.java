package com.inboxintelligence.ingester.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import com.inboxintelligence.ingester.config.GmailApiProperties;
import com.inboxintelligence.ingester.domain.GmailSyncService;
import com.inboxintelligence.ingester.model.GmailEvent;
import com.inboxintelligence.persistence.model.entity.GmailMailbox;
import com.inboxintelligence.persistence.service.GmailMailboxService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static com.inboxintelligence.persistence.model.SyncStatus.DISCONNECTED;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class GmailPubSubSubscriber {

    private final ObjectMapper objectMapper;
    private final GmailSyncService gmailSyncService;
    private final GmailApiProperties gmailApiProperties;
    private final GmailMailboxService gmailMailboxService;

    private Subscriber subscriber;

    @PostConstruct
    public void start() throws IOException {

        ProjectSubscriptionName projectSubscriptionName = ProjectSubscriptionName.of(gmailApiProperties.projectId(), gmailApiProperties.pubsubSubscriptionId());

        Path keyPath = Path.of(gmailApiProperties.serviceAccountKeyPath()).toAbsolutePath();
        log.info("Loading GCP credentials from: {}", keyPath);
        GoogleCredentials credentials;
        try (FileInputStream fis = new FileInputStream(keyPath.toFile())) {
            credentials = GoogleCredentials.fromStream(fis);
        }

        subscriber = Subscriber.newBuilder(projectSubscriptionName, this::handleMessage)
                .setCredentialsProvider(() -> credentials)
                .build();
        subscriber.startAsync().awaitRunning();
        log.info("Gmail Pub/Sub subscriber started for subscription={}", projectSubscriptionName);
    }

    @PreDestroy
    public void stop() {

        if (subscriber != null) {
            subscriber.stopAsync();
            log.info("Gmail Pub/Sub subscriber stopped");
        }
    }

    public void handleMessage(PubsubMessage message, AckReplyConsumer consumer) {

        GmailMailbox gmailMailbox = null;

        try {
            String payload = message.getData().toStringUtf8();
            log.info("Received Gmail Pub/Sub payload: {}", payload);

            GmailEvent event = objectMapper.readValue(payload, GmailEvent.class);
            Optional<GmailMailbox> gmailMailboxOptional = gmailMailboxService.findByEmailAddress(event.emailAddress());

            if (gmailMailboxOptional.isEmpty()) {
                log.warn("Mailbox not found for email {}", event.emailAddress());
                consumer.ack();
                return;
            }

            gmailMailbox = gmailMailboxOptional.get();
            gmailSyncService.triggerSyncJob(gmailMailbox, event.historyId());
            consumer.ack();

        } catch (Exception e) {

            if (gmailMailbox != null && hasInvalidGrant(e)) {

                log.error("Refresh token revoked for {}", gmailMailbox.getEmailAddress());

                gmailMailbox.setSyncStatus(DISCONNECTED);
                gmailMailbox.setLastSyncError("Refresh token revoked");
                gmailMailboxService.save(gmailMailbox);
                consumer.ack();
                return;
            }

            log.error("Failed to process Gmail Pub/Sub message", e);
            consumer.nack();
        }
    }

    private boolean hasInvalidGrant(Throwable throwable) {

        while (throwable != null) {
            String message = throwable.getMessage();
            if (message != null && message.contains("invalid_grant")) {
                return true;
            }
            throwable = throwable.getCause();
        }

        return false;
    }
}
