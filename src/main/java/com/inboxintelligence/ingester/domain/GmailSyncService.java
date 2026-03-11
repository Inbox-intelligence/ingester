package com.inboxintelligence.ingester.domain;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.*;
import com.inboxintelligence.ingester.model.ResolvedAttachment;
import com.inboxintelligence.ingester.model.StoredEmailContentPaths;
import com.inboxintelligence.ingester.model.entity.EmailContent;
import com.inboxintelligence.ingester.model.entity.GmailMailbox;
import com.inboxintelligence.ingester.outbound.GmailApiClient;
import com.inboxintelligence.ingester.outbound.GmailClientFactory;
import com.inboxintelligence.ingester.persistence.service.EmailContentService;
import com.inboxintelligence.ingester.persistence.service.GmailMailboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static com.inboxintelligence.ingester.utils.Base64Utils.decodeBase64Bytes;

/**
 * Orchestrates Gmail mailbox synchronisation.
 * <p>
 * Handles concurrency control and the sync loop, but delegates all
 * heavy lifting to focused collaborators:
 * <ul>
 *   <li>{@link GmailApiClient} — Gmail API calls with retry</li>
 *   <li>{@link GmailMimeContentExtractor} — MIME tree parsing</li>
 *   <li>{@link EmailContentStorageService} — attachment persistence</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GmailSyncService {

    private final GmailClientFactory gmailClientFactory;
    private final GmailApiClient gmailApiClient;
    private final GmailMimeContentExtractor gmailMimeContentExtractor;
    private final EmailContentStorageService gmailContentStorageService;
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

            String subject = gmailMimeContentExtractor.getHeader(message, "Subject");
            String from = gmailMimeContentExtractor.getHeader(message, "From");
            String to = gmailMimeContentExtractor.getHeader(message, "To");
            String cc = gmailMimeContentExtractor.getHeader(message, "Cc");

            GmailMimeContentExtractor.ExtractedContent extracted =
                    gmailMimeContentExtractor.extract(message.getPayload());

            log.info("Email received {}: {} <from:{}> <to:{}>", messageId, subject, from, to);

            StoredEmailContentPaths contentPaths =
                    gmailContentStorageService.storeEmailContent(
                            mailbox.getId(), messageId,
                            message.toPrettyString(), extracted.textBody(), extracted.htmlBody());

            EmailContent newEmail = EmailContent.builder()
                    .gmailMailboxId(mailbox.getId())
                    .messageId(message.getId())
                    .threadId(message.getThreadId())
                    .parentMessageId(gmailMimeContentExtractor.getHeader(message, "In-Reply-To"))
                    .rawMessagePath(contentPaths.rawMessagePath())
                    .subject(subject)
                    .fromAddress(from)
                    .toAddress(to)
                    .ccAddress(cc)
                    .bodyContentPath(contentPaths.bodyContentPath())
                    .bodyHtmlContentPath(contentPaths.bodyHtmlContentPath())
                    .sentAt(gmailMimeContentExtractor.parseInternalDate(message))
                    .receivedAt(gmailMimeContentExtractor.parseInternalDate(message))
                    .isProcessed(false)
                    .build();

            EmailContent savedEmail = emailContentService.save(newEmail);

            log.info("Email saved {}: {}", messageId, subject);

            List<ResolvedAttachment> resolvedAttachments =
                    resolveAttachments(gmail, messageId, extracted.attachmentParts());

            gmailContentStorageService.processAttachments(
                    mailbox.getId(), savedEmail, messageId, resolvedAttachments);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private List<ResolvedAttachment> resolveAttachments(
            Gmail gmail, String messageId, List<MessagePart> attachmentParts) {

        if (CollectionUtils.isEmpty(attachmentParts)) {
            return List.of();
        }

        List<ResolvedAttachment> resolved = new ArrayList<>();

        for (MessagePart part : attachmentParts) {
            try {
                byte[] data = fetchAttachmentData(gmail, messageId, part);

                if (data == null || data.length == 0) {
                    log.warn("Empty attachment data for '{}' in message {}", part.getFilename(), messageId);
                    continue;
                }

                String fileName = StringUtils.hasText(part.getFilename())
                        ? part.getFilename()
                        : "unnamed_" + System.currentTimeMillis();

                String attachmentId = (part.getBody() != null) ? part.getBody().getAttachmentId() : null;
                long sizeInBytes = (part.getBody() != null && part.getBody().getSize() != null)
                        ? part.getBody().getSize()
                        : data.length;

                String contentId = getPartHeader(part, "Content-ID");

                resolved.add(new ResolvedAttachment(
                        fileName, part.getMimeType(), attachmentId, sizeInBytes, data, isInlinePart(part), contentId));

            } catch (Exception e) {
                log.error("Failed to fetch attachment '{}' for message {}: {}",
                        part.getFilename(), messageId, e.getMessage());
            }
        }

        return resolved;
    }

    private byte[] fetchAttachmentData(Gmail gmail, String messageId, MessagePart part) {

        MessagePartBody body = part.getBody();

        if (body == null) {
            return null;
        }

        if (StringUtils.hasText(body.getData())) {
            return decodeBase64Bytes(body.getData());
        }

        if (StringUtils.hasText(body.getAttachmentId())) {
            MessagePartBody attachmentBody = gmailApiClient.fetchAttachment(gmail, messageId, body.getAttachmentId());
            if (attachmentBody != null && StringUtils.hasText(attachmentBody.getData())) {
                return decodeBase64Bytes(attachmentBody.getData());
            }
        }

        return null;
    }

    private boolean isInlinePart(MessagePart part) {

        if (part.getHeaders() == null) {
            return false;
        }

        for (MessagePartHeader header : part.getHeaders()) {
            if ("Content-Disposition".equalsIgnoreCase(header.getName())) {
                return header.getValue().toLowerCase().startsWith("inline");
            }
        }

        return false;
    }

    private String getPartHeader(MessagePart part, String name) {

        if (part.getHeaders() == null) {
            return null;
        }

        for (MessagePartHeader header : part.getHeaders()) {
            if (name.equalsIgnoreCase(header.getName())) {
                return header.getValue();
            }
        }

        return null;
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
