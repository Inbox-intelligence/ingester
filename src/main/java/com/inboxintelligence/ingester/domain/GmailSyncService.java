package com.inboxintelligence.ingester.domain;


import com.google.api.services.gmail.model.*;
import com.inboxintelligence.ingester.model.entity.EmailContent;
import com.inboxintelligence.ingester.model.entity.GmailMailbox;
import com.inboxintelligence.ingester.persistence.service.EmailContentService;
import com.inboxintelligence.ingester.persistence.service.GmailMailboxService;
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

    public void triggerSyncJob(GmailMailbox gmailMailbox, Long eventHistoryId) throws IOException {

        String mailboxEmailAddress = gmailMailbox.getEmailAddress();

        mailboxMaxHistory.merge(mailboxEmailAddress, eventHistoryId, Math::max);
        ReentrantLock lock = mailboxLocks.computeIfAbsent(mailboxEmailAddress, key -> new ReentrantLock());

        if (gmailMailbox.getHistoryId() > eventHistoryId) {
            log.info("Ignoring stale Gmail event");
            return;
        }

        if (!lock.tryLock()) {
            log.info("Sync already running for mailbox {}", mailboxEmailAddress);
            return;
        }

        try {
            do {
                Long lastHistoryIdSynced = gmailMailbox.getHistoryId();
                Long latestMaxHistoryId = mailboxMaxHistory.getOrDefault(mailboxEmailAddress, lastHistoryIdSynced);

                if (lastHistoryIdSynced >= latestMaxHistoryId){
                    log.info("Ignoring stale Gmail event: {} {}", mailboxEmailAddress, lastHistoryIdSynced);
                    break;
                }
                syncGmailMailbox(gmailMailbox);

            } while (true);

        } finally {
            lock.unlock();
            mailboxLocks.remove(mailboxEmailAddress, lock);
        }
    }


    public void syncGmailMailbox(GmailMailbox gmailMailbox) throws IOException {

        log.info("Triggering Sync Job: {}", gmailMailbox.getEmailAddress());

        Long latestHistoryId = null;
        String pageToken = null;
        var gmail = gmailClientFactory.createUsingRefreshToken(gmailMailbox.getRefreshToken());

        do {
            var request = gmail.users()
                    .history()
                    .list("me")
                    .setStartHistoryId(BigInteger.valueOf(gmailMailbox.getHistoryId()))
                    .setPageToken(pageToken);

            var response = request.execute();

            if (response == null) {
                log.error("Stopping: Gmail returned null response");
                break;
            }

            processListHistoryResponse(gmailMailbox, response.getHistory());
            pageToken = response.getNextPageToken();
            latestHistoryId = response.getHistoryId().longValue();

        } while (pageToken != null);

        if (latestHistoryId != null) {

            log.info("Setting latest history Id {}", latestHistoryId);
            gmailMailbox.setHistoryId(latestHistoryId);
            gmailMailboxService.save(gmailMailbox);

        }
    }

    private void processListHistoryResponse(GmailMailbox gmailMailbox, List<History> historyList) {

        if (CollectionUtils.isEmpty(historyList)) {
            log.error("Stopping: Gmail returned empty historyList");
            return;
        }

        for (History history : historyList) {

            if (history == null) {
                continue;
            }

            if (!CollectionUtils.isEmpty(history.getMessagesAdded())) {
                history.getMessagesAdded().forEach(historyMessageAdded -> processNewMessageAdded(gmailMailbox, historyMessageAdded));
            }

            if (!CollectionUtils.isEmpty(history.getMessagesDeleted())) {
                history.getMessagesDeleted().forEach(historyMessageDeleted -> processMessagesDeleted(historyMessageDeleted, gmailMailbox));
            }
        }
    }

    private void processNewMessageAdded(GmailMailbox gmailMailbox, HistoryMessageAdded historyMessageAdded) {

        if (historyMessageAdded == null || historyMessageAdded.getMessage() == null) {
            return;
        }

        String messageId = historyMessageAdded.getMessage().getId();

        if (emailContentService.existsByGmailMailboxIdAndMessageId(gmailMailbox.getId(), messageId)) {
            return;
        }

        try {

            var gmail = gmailClientFactory.createUsingRefreshToken(gmailMailbox.getRefreshToken());

            var message = gmail.users()
                    .messages()
                    .get("me", messageId)
                    .setFormat("full")
                    .execute();

            String subject = getHeader(message, "Subject");
            String from = getHeader(message, "From");
            String to = getHeader(message, "To");
            String cc = getHeader(message, "Cc");
            String body = extractBody(message);


            log.info("Email received {}: {} <from: {}> <to: {}>", messageId, subject, from, to);

            var newEmail = EmailContent.builder()
                    .gmailMailboxId(gmailMailbox.getId())
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

            log.info("Email saved {}: {} <from: {}> <to: {}>", messageId, subject, from, to);



        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private String getHeader(Message message, String name) {

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

        if (payload.getParts() == null) {
            return decodeBase64String(payload.getBody().getData());
        }

        for (var part : payload.getParts()) {

            if ("text/plain".equalsIgnoreCase(part.getMimeType())) {
                return decodeBase64String(part.getBody().getData());
            }
        }

        // fallback to html
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




    private void processMessagesDeleted(HistoryMessageDeleted historyMessageDeleted, GmailMailbox gmailMailbox) {
        //add Code

    }
}
