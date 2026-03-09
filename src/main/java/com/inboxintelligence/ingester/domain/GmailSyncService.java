package com.inboxintelligence.ingester.domain;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.HistoryMessageAdded;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.Message;
import com.inboxintelligence.ingester.model.entity.EmailContent;
import com.inboxintelligence.ingester.model.entity.GmailMailbox;
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

/**
 * Orchestrates Gmail mailbox synchronisation.
 * <p>
 * Handles concurrency control and the sync loop, but delegates all
 * heavy lifting to focused collaborators:
 * <ul>
 *   <li>{@link GmailApiClient} — Gmail API calls with retry</li>
 *   <li>{@link MimeContentExtractor} — MIME tree parsing</li>
 *   <li>{@link AttachmentStorageService} — attachment download &amp; storage</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GmailSyncService {

    private final GmailClientFactory gmailClientFactory;
    private final GmailApiClient gmailApiClient;
    private final MimeContentExtractor mimeContentExtractor;
    private final AttachmentStorageService attachmentStorageService;
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

            ListHistoryResponse response = gmailApiClient.fetchHistory(
                    gmail, mailbox.getHistoryId(), pageToken);

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
                history.getMessagesAdded()
                        .forEach(msg -> processNewMessageAdded(gmail, mailbox, msg));
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

            String subject = mimeContentExtractor.getHeader(message, "Subject");
            String from = mimeContentExtractor.getHeader(message, "From");
            String to = mimeContentExtractor.getHeader(message, "To");
            String cc = mimeContentExtractor.getHeader(message, "Cc");

            MimeContentExtractor.ExtractedContent extracted =
                    mimeContentExtractor.extract(message.getPayload());

            log.info("Email received {}: {} <from:{}> <to:{}>", messageId, subject, from, to);

            EmailContent newEmail = EmailContent.builder()
                    .gmailMailboxId(mailbox.getId())
                    .messageId(message.getId())
                    .threadId(message.getThreadId())
                    .parentMessageId(mimeContentExtractor.getHeader(message, "In-Reply-To"))
                    .rawMessage(message.toPrettyString())
                    .subject(subject)
                    .fromAddress(from)
                    .toAddress(to)
                    .ccAddress(cc)
                    .body(extracted.textBody())
                    .bodyHtml(extracted.htmlBody())
                    .sentAt(mimeContentExtractor.parseInternalDate(message))
                    .receivedAt(mimeContentExtractor.parseInternalDate(message))
                    .isProcessed(false)
                    .build();

            EmailContent savedEmail = emailContentService.save(newEmail);

            log.info("Email saved {}: {}", messageId, subject);

            attachmentStorageService.processAttachments(
                    gmail, mailbox.getId(), savedEmail, messageId, extracted.attachmentParts());

        } catch (IOException e) {
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
