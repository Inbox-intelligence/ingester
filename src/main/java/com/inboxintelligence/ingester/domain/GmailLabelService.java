package com.inboxintelligence.ingester.domain;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.inboxintelligence.ingester.outbound.GmailApiClient;
import com.inboxintelligence.ingester.outbound.GmailClientFactory;
import com.inboxintelligence.persistence.model.entity.GmailMailbox;
import com.inboxintelligence.persistence.service.GmailMailboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailLabelService {

    private static final String GMAIL_LABEL_TYPE_USER = "user";

    private final GmailClientFactory gmailClientFactory;
    private final GmailMailboxService gmailMailboxService;
    private final GmailApiClient gmailApiClient;
    private final GmailLabelProcessingService gmailLabelProcessingService;

    public Map<String, Object> fetchLabels(String mailboxAddress) {

        GmailMailbox mailbox = gmailMailboxService.findByEmailAddress(mailboxAddress)
                .orElseThrow(() -> new IllegalArgumentException("Mailbox not found: " + mailboxAddress));

        log.info("Label sync started for {}", mailbox.getEmailAddress());

        Gmail gmail = gmailClientFactory.createUsingRefreshToken(mailbox.getRefreshToken());

        ListLabelsResponse response = gmailApiClient.listLabels(gmail);

        if (response == null || response.getLabels() == null) {
            log.warn("Label sync for {}: Gmail returned null response", mailbox.getEmailAddress());
            return Map.of("inbound", 0, "created", 0, "deleted", 0);
        }

        Set<String> gmailLabelFullNames = response.getLabels().stream()
                .filter(label -> GMAIL_LABEL_TYPE_USER.equalsIgnoreCase(label.getType()))
                .map(Label::getName)
                .collect(Collectors.toSet());

        Map<String, Object> result = gmailLabelProcessingService.processGmailLabels(mailbox.getId(), gmailLabelFullNames);

        log.info("Label sync done for {}: {}", mailbox.getEmailAddress(), result);

        return result;
    }
}
