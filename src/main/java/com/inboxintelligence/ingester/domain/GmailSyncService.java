package com.inboxintelligence.ingester.domain;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.*;
import com.inboxintelligence.ingester.exception.RetryableGmailApiException;
import com.inboxintelligence.ingester.model.entity.EmailContent;
import com.inboxintelligence.ingester.model.entity.GmailMailbox;
import com.inboxintelligence.ingester.persistence.service.EmailContentService;
import com.inboxintelligence.ingester.persistence.service.GmailMailboxService;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static com.inboxintelligence.ingester.utils.Base64Utils.decodeBase64String;

@Service
@Slf4j
@RequiredArgsConstructor
public class GmailSyncService {

    private final GmailClientFactory gmailClientFactory;
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

            ListHistoryResponse response = fetchHistory(gmail, mailbox, pageToken);

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


    private ListHistoryResponse fetchHistory(Gmail gmail, GmailMailbox mailbox, String pageToken) throws IOException {

        var request = gmail.users()
                .history()
                .list("me")
                .setStartHistoryId(BigInteger.valueOf(mailbox.getHistoryId()))
                .setPageToken(pageToken);

        return executeHistoryRequest(request);
    }


    @Retry(name = "gmailRetry")
    private ListHistoryResponse executeHistoryRequest(Gmail.Users.History.List request) {

        try {
            return request.execute();
        } catch (GoogleJsonResponseException ex) {

            int status = ex.getStatusCode();

            if (status == 429 || status >= 500) {
                throw new RetryableGmailApiException("Retrying executeHistoryRequest: Received " + status);
            }

            throw new IllegalArgumentException("Non retryable Gmail error", ex);

        } catch (IOException e) {
            throw new RetryableGmailApiException("Retrying executeHistoryRequest: " + e.getMessage());
        }
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

            Message message = fetchMessage(gmail, messageId);

            String subject = getHeader(message, "Subject");
            String from = getHeader(message, "From");
            String to = getHeader(message, "To");
            String cc = getHeader(message, "Cc");

            String body = extractBody(message);

            log.info("Email received {}: {} <from:{}> <to:{}>", messageId, subject, from, to);

            EmailContent newEmail = EmailContent.builder()
                    .gmailMailboxId(mailbox.getId())
                    .messageId(message.getId())
                    .threadId(message.getThreadId())
                    .parentMessageId(getHeader(message, "In-Reply-To"))
                    .rawMessage(message.toPrettyString())
                    .subject(subject)
                    .fromAddress(from)
                    .toAddress(to)
                    .ccAddress(cc)
                    .body(body)
                    .sentAt(parseInternalDate(message))
                    .receivedAt(parseInternalDate(message))
                    .isProcessed(false)
                    .build();

            emailContentService.save(newEmail);

            log.info("Email saved {}: {}", messageId, subject);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private Message fetchMessage(Gmail gmail, String messageId) throws IOException {

        var request = gmail.users()
                .messages()
                .get("me", messageId)
                .setFormat("full");

        return executeMessageRequest(request);
    }


    @Retry(name = "gmailRetry")
    private Message executeMessageRequest(Gmail.Users.Messages.Get request) {

        try {
            return request.execute();
        } catch (IOException e) {
            throw new RetryableGmailApiException("Retrying message fetch: " + e.getMessage());
        }
    }



    private String getHeader(Message message, String name) {

        if (message.getPayload() == null || message.getPayload().getHeaders() == null) {
            return null;
        }

        return message.getPayload()
                .getHeaders()
                .stream()
                .filter(h -> name.equalsIgnoreCase(h.getName()))
                .map(MessagePartHeader::getValue)
                .findFirst()
                .orElse(null);
    }


    private String extractBody(Message message) {

        var payload = message.getPayload();

        if (payload == null) {
            return null;
        }

        if (payload.getParts() == null) {
            return decodeBase64String(payload.getBody().getData());
        }

        for (var part : payload.getParts()) {

            if ("text/plain".equalsIgnoreCase(part.getMimeType())) {
                return decodeBase64String(part.getBody().getData());
            }
        }

        for (var part : payload.getParts()) {

            if ("text/html".equalsIgnoreCase(part.getMimeType())) {
                return decodeBase64String(part.getBody().getData());
            }
        }

        return null;
    }


    private LocalDateTime parseInternalDate(Message message) {

        if (message.getInternalDate() == null) {
            return null;
        }

        return Instant.ofEpochMilli(message.getInternalDate())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
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