package com.inboxintelligence.ingester.domain.message;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.inboxintelligence.ingester.outbound.GmailApiClient;
import com.inboxintelligence.ingester.outbound.GmailClientFactory;
import com.inboxintelligence.persistence.model.EmailOrigin;
import com.inboxintelligence.persistence.model.entity.GmailMailbox;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.service.GmailMailboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailMessageBackfillService {

    private final GmailClientFactory gmailClientFactory;
    private final GmailApiClient gmailApiClient;
    private final GmailMessageProcessingService gmailMessageProcessingService;
    private final GmailMailboxService gmailMailboxService;
    private final EmailContentService emailContentService;

    public void backfill(String mailboxAddress, String query) {

        GmailMailbox mailbox = gmailMailboxService.findByEmailAddress(mailboxAddress)
                .orElseThrow(() -> new IllegalArgumentException("Mailbox not found: " + mailboxAddress));

        log.info("Backfill started for {} q='{}'", mailbox.getEmailAddress(), query);

        Set<String> knownMessageIds = new HashSet<>(emailContentService.findMessageIdsByGmailMailboxId(mailbox.getId()));
        log.debug("Backfill: pre-loaded {} known message ids for {}", knownMessageIds.size(), mailbox.getEmailAddress());

        Gmail gmail = gmailClientFactory.createUsingRefreshToken(mailbox.getRefreshToken());

        String pageToken = null;
        int total = 0;
        int processed = 0;
        int skipped = 0;
        int failed = 0;

        do {
            ListMessagesResponse response = gmailApiClient.listMessages(gmail, query, pageToken);

            if (response == null || response.getMessages() == null) {
                break;
            }

            for (Message stub : response.getMessages()) {

                total++;

                if (knownMessageIds.contains(stub.getId())) {
                    skipped++;
                    continue;
                }

                try {
                    Message message = gmailApiClient.fetchMessage(gmail, stub.getId());
                    gmailMessageProcessingService.process(gmail, mailbox, message, EmailOrigin.BACKFILL);
                    knownMessageIds.add(stub.getId());
                    processed++;
                } catch (Exception e) {
                    log.error("Backfill: failed to process message {} for mailbox {}: {}", stub.getId(), mailbox.getEmailAddress(), e.getMessage());
                    failed++;
                }
            }

            pageToken = response.getNextPageToken();

        } while (pageToken != null);

        log.info("Backfill done for {}: total={} processed={} skipped={} failed={}",
                mailbox.getEmailAddress(), total, processed, skipped, failed);
    }
}
