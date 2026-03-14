package com.inboxintelligence.ingester.domain;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.*;
import com.inboxintelligence.ingester.model.entity.GmailMailbox;
import com.inboxintelligence.ingester.outbound.GmailApiClient;
import com.inboxintelligence.ingester.outbound.GmailClientFactory;
import com.inboxintelligence.ingester.persistence.service.EmailContentService;
import com.inboxintelligence.ingester.persistence.service.GmailMailboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Handles Gmail mailbox sync concurrency control and trigger logic. */
@Service
@Slf4j
@RequiredArgsConstructor
public class GmailSyncService {

    private final GmailClientFactory gmailClientFactory;
    private final GmailApiClient gmailApiClient;
    private final GmailMessageProcessingService gmailMessageProcessingService;
    private final GmailMailboxService gmailMailboxService;
    private final EmailContentService emailContentService;

    private final ConcurrentHashMap<String, ReentrantLock> mailboxLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> mailboxMaxHistory = new ConcurrentHashMap<>();

    public void triggerSyncJob(GmailMailbox mailbox, Long eventHistoryId) throws IOException {

        String email = mailbox.getEmailAddress();
        mailboxMaxHistory.merge(email, eventHistoryId, Math::max);
        ReentrantLock lock = mailboxLocks.computeIfAbsent(email, k -> new ReentrantLock());

        if (mailbox.getHistoryId() > eventHistoryId) {
            log.info("Ignoring stale Gmail event");
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
        }
    }

    private void runSyncLoop(GmailMailbox mailbox) throws IOException {

        String email = mailbox.getEmailAddress();

        while (true) {

            long lastSynced = mailbox.getHistoryId();
            long latestEvent = mailboxMaxHistory.getOrDefault(email, lastSynced);

            if (lastSynced >= latestEvent) {
                log.info("No new Gmail events {} {}", email, lastSynced);
                return;
            }

            syncGmailMailbox(mailbox);
        }
    }

    public void syncGmailMailbox(GmailMailbox mailbox) throws IOException {

        log.info("Syncing mailbox {}", mailbox.getEmailAddress());
        Gmail gmail = gmailClientFactory.createUsingRefreshToken(mailbox.getRefreshToken());
        String pageToken = null;
        Long latestHistoryId = null;

        do {

            ListHistoryResponse response = gmailApiClient.fetchHistory(gmail, mailbox.getHistoryId(), pageToken);

            if (response == null) {
                log.error("Gmail returned null response");
                return;
            }

            processHistory(gmail, mailbox, response.getHistory());

            pageToken = response.getNextPageToken();
            latestHistoryId = response.getHistoryId().longValue();

        } while (pageToken != null);

        updateMailboxHistory(mailbox, latestHistoryId);
    }

    private void processHistory(Gmail gmail, GmailMailbox mailbox, List<History> historyList) {

        if (CollectionUtils.isEmpty(historyList)) {
            return;
        }

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
            return;
        }

        try {
            Message message = gmailApiClient.fetchMessage(gmail, messageId);
            gmailMessageProcessingService.process(gmail, mailbox.getId(), message);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void updateMailboxHistory(GmailMailbox mailbox, Long historyId) {

        if (historyId == null) {
            return;
        }

        log.info("Updating historyId {}", historyId);

        mailbox.setHistoryId(historyId);
        gmailMailboxService.save(mailbox);
    }
}
