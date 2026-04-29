package com.inboxintelligence.ingester.domain;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.HistoryMessageAdded;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.Message;
import com.inboxintelligence.ingester.exception.MessageNotFoundException;
import com.inboxintelligence.ingester.outbound.GmailApiClient;
import com.inboxintelligence.ingester.outbound.GmailClientFactory;
import com.inboxintelligence.persistence.model.EmailOrigin;
import com.inboxintelligence.persistence.model.entity.GmailMailbox;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.service.GmailMailboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailSyncService {

    private static final int MAX_SYNC_ITERATIONS = 50;

    private final GmailClientFactory gmailClientFactory;
    private final GmailApiClient gmailApiClient;
    private final GmailMessageProcessingService gmailMessageProcessingService;
    private final GmailMailboxService gmailMailboxService;
    private final EmailContentService emailContentService;

    private final ConcurrentHashMap<String, ReentrantLock> mailboxLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> mailboxMaxHistory = new ConcurrentHashMap<>();

    public void triggerSyncJob(GmailMailbox mailbox, Long eventHistoryId) throws IOException {

        String email = mailbox.getEmailAddress();
        log.debug("Sync triggered for {} with eventHistoryId {}", email, eventHistoryId);

        mailboxMaxHistory.merge(email, eventHistoryId, Math::max);
        ReentrantLock lock = mailboxLocks.computeIfAbsent(email, k -> new ReentrantLock());

        if (mailbox.getHistoryId() > eventHistoryId) {
            log.info("Ignoring stale Gmail event for {} (mailbox historyId {} > event historyId {})", email, mailbox.getHistoryId(), eventHistoryId);
            mailboxMaxHistory.remove(email);
            return;
        }

        if (!lock.tryLock()) {
            log.info("Sync already running for {}", email);
            return;
        }

        try {
            runSyncLoop(mailbox);
        } finally {
            lock.unlock();
            mailboxLocks.remove(email, lock);
            mailboxMaxHistory.remove(email);
        }
    }

    private void runSyncLoop(GmailMailbox mailbox) throws IOException {

        String email = mailbox.getEmailAddress();

        for (int i = 0; i < MAX_SYNC_ITERATIONS; i++) {

            long lastSynced = mailbox.getHistoryId();
            long latestEvent = mailboxMaxHistory.getOrDefault(email, lastSynced);

            if (lastSynced >= latestEvent) {
                log.info("No new Gmail events {} {}", email, lastSynced);
                return;
            }

            syncGmailMailbox(mailbox);
        }

        log.warn("Sync loop reached max iterations ({}) for {}", MAX_SYNC_ITERATIONS, mailbox.getEmailAddress());
    }

    private void syncGmailMailbox(GmailMailbox mailbox) throws IOException {

        log.info("Syncing mailbox {} from historyId {}", mailbox.getEmailAddress(), mailbox.getHistoryId());
        Gmail gmail = gmailClientFactory.createUsingRefreshToken(mailbox.getRefreshToken());
        String pageToken = null;
        Long latestHistoryId = null;

        do {

            ListHistoryResponse response = gmailApiClient.fetchHistory(gmail, mailbox.getHistoryId(), pageToken);

            if (response == null) {
                log.error("Gmail returned null history response for {}", mailbox.getEmailAddress());
                return;
            }

            processHistory(gmail, mailbox, response.getHistory());

            pageToken = response.getNextPageToken();
            latestHistoryId = response.getHistoryId().longValue();
            log.debug("History page fetched for {}, latestHistoryId={}, nextPageToken={}", mailbox.getEmailAddress(), latestHistoryId, pageToken);

        } while (pageToken != null);

        updateMailboxHistory(mailbox, latestHistoryId);
    }

    private void processHistory(Gmail gmail, GmailMailbox mailbox, List<History> historyList) {

        if (CollectionUtils.isEmpty(historyList)) {
            log.debug("No history entries to process for {}", mailbox.getEmailAddress());
            return;
        }

        log.debug("Processing {} history entries for {}", historyList.size(), mailbox.getEmailAddress());

        for (History history : historyList) {

            if (history == null) {
                continue;
            }

            if (!CollectionUtils.isEmpty(history.getMessagesAdded())) {
                history.getMessagesAdded().forEach(msg -> processNewMessageAdded(gmail, mailbox, msg));
            }
        }
    }

    private void processNewMessageAdded(Gmail gmail, GmailMailbox mailbox, HistoryMessageAdded historyMessageAdded) {

        if (historyMessageAdded == null || historyMessageAdded.getMessage() == null) {
            return;
        }

        String messageId = historyMessageAdded.getMessage().getId();
        if (emailContentService.existsByGmailMailboxIdAndMessageId(mailbox.getId(), messageId)) {
            log.debug("Skipping duplicate message {} for {}", messageId, mailbox.getEmailAddress());
            return;
        }

        try {
            Message message = gmailApiClient.fetchMessage(gmail, messageId);
            gmailMessageProcessingService.process(gmail, mailbox.getId(), message, EmailOrigin.PUB_SUB);
        } catch (MessageNotFoundException e) {
            log.warn("Message {} no longer exists for {} — skipping", messageId, mailbox.getEmailAddress());
        } catch (Exception e) {
            log.error("Failed to process message {} for mailbox {}", messageId, mailbox.getEmailAddress(), e);
        }
    }

    private void updateMailboxHistory(GmailMailbox mailbox, Long historyId) {

        if (historyId == null) {
            return;
        }

        log.info("Updating historyId {} for {}", historyId, mailbox.getEmailAddress());

        mailbox.setHistoryId(historyId);
        gmailMailboxService.save(mailbox);
    }
}
