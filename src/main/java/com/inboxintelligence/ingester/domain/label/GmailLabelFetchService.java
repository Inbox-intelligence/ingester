package com.inboxintelligence.ingester.domain.label;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.inboxintelligence.ingester.outbound.GmailApiClient;
import com.inboxintelligence.ingester.outbound.GmailClientFactory;
import com.inboxintelligence.persistence.model.entity.GmailMailbox;
import com.inboxintelligence.persistence.model.entity.Label;
import com.inboxintelligence.persistence.service.GmailMailboxService;
import com.inboxintelligence.persistence.service.LabelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailLabelFetchService {

    private static final String GMAIL_LABEL_TYPE_USER = "user";

    private final GmailClientFactory gmailClientFactory;
    private final GmailMailboxService gmailMailboxService;
    private final GmailApiClient gmailApiClient;
    private final LabelService labelService;

    private final ConcurrentHashMap<String, ReentrantLock> mailboxLocks = new ConcurrentHashMap<>();

    public Map<String, Object> fetchLabels(String mailboxAddress) {

        ReentrantLock lock = mailboxLocks.computeIfAbsent(mailboxAddress, k -> new ReentrantLock());
        lock.lock();
        try {
            GmailMailbox mailbox = gmailMailboxService.findByEmailAddress(mailboxAddress)
                    .orElseThrow(() -> new IllegalArgumentException("Mailbox not found: " + mailboxAddress));

            log.info("Label fetch started for {}", mailbox.getEmailAddress());

            Gmail gmail = gmailClientFactory.createUsingRefreshToken(mailbox.getRefreshToken());
            ListLabelsResponse response = gmailApiClient.listLabels(gmail);

            if (response == null || response.getLabels() == null || response.getLabels().isEmpty()) {
                log.warn("Label fetch for {}: Gmail returned no labels — refusing to flush local rows", mailbox.getEmailAddress());
                return Map.of("success", false, "inbound", 0, "skipped", "empty-response");
            }

            Set<String> gmailLabelFullNames = response.getLabels().stream()
                    .filter(label -> GMAIL_LABEL_TYPE_USER.equalsIgnoreCase(label.getType()))
                    .map(com.google.api.services.gmail.model.Label::getName)
                    .collect(Collectors.toSet());

            List<Label> labels = gmailLabelFullNames.stream()
                    .map(fullName -> Label.builder()
                            .gmailMailboxId(mailbox.getId())
                            .displayName(LabelNameUtils.extractDisplayName(fullName))
                            .fullName(fullName)
                            .build())
                    .toList();

            labelService.flushAndFillLabels(mailbox.getId(), labels);

            log.info("Label fetch done for {}: count={}", mailbox.getEmailAddress(), labels.size());
            return Map.of("success", true, "inbound", labels.size());
        } finally {
            lock.unlock();
        }
    }
}
