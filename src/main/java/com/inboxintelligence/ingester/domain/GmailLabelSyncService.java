package com.inboxintelligence.ingester.domain;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.inboxintelligence.ingester.outbound.GmailApiClient;
import com.inboxintelligence.ingester.outbound.GmailClientFactory;
import com.inboxintelligence.persistence.model.LabelSource;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailLabelSyncService {

    private static final String GMAIL_LABEL_TYPE_USER = "user";

    private final GmailClientFactory gmailClientFactory;
    private final GmailApiClient gmailApiClient;
    private final GmailMailboxService gmailMailboxService;
    private final LabelService labelService;

    public void syncForMailbox(Long mailboxId) {

        GmailMailbox mailbox = gmailMailboxService.findById(mailboxId)
                .orElseThrow(() -> new IllegalArgumentException("Mailbox not found: " + mailboxId));

        log.info("Label catalog sync started for {}", mailbox.getEmailAddress());

        Gmail gmail = gmailClientFactory.createUsingRefreshToken(mailbox.getRefreshToken());

        Set<String> gmailFullNames = fetchGmailUserLabelFullNames(gmail);

        int upsertCount = upsertNewOrReactivatedLabels(mailbox.getId(), gmailFullNames);
        int deactivateCount = deactivateMissingUserLabels(mailbox.getId(), gmailFullNames);

        log.info("Label catalog sync done for {}: upserts={}, deactivations={}",
                mailbox.getEmailAddress(), upsertCount, deactivateCount);
    }

    private Set<String> fetchGmailUserLabelFullNames(Gmail gmail) {

        ListLabelsResponse response = gmailApiClient.listLabels(gmail);
        if (response == null || response.getLabels() == null) {
            return Set.of();
        }
        return response.getLabels().stream()
                .filter(label -> GMAIL_LABEL_TYPE_USER.equalsIgnoreCase(label.getType()))
                .map(com.google.api.services.gmail.model.Label::getName)
                .collect(Collectors.toSet());
    }

    private int upsertNewOrReactivatedLabels(Long mailboxId, Set<String> gmailFullNames) {
        return (int) gmailFullNames.stream()
                .filter(fullName -> labelService.upsertUserLabel(mailboxId, extractDisplayName(fullName), fullName))
                .count();
    }

    private int deactivateMissingUserLabels(Long mailboxId, Set<String> gmailFullNames) {

        Map<String, Label> dbUserLabelByFullName = labelService.findByMailboxIdAndSource(mailboxId, LabelSource.USER).stream()
                .collect(Collectors.toMap(Label::getFullName, label -> label));

        List<Label> toDeactivate = dbUserLabelByFullName.entrySet().stream()
                .filter(entry -> !gmailFullNames.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(label -> Boolean.TRUE.equals(label.getIsActive()))
                .toList();

        toDeactivate.forEach(labelService::deactivateUserLabel);
        return toDeactivate.size();
    }

    private String extractDisplayName(String fullName) {
        int lastSlash = fullName.lastIndexOf('/');
        return lastSlash >= 0 ? fullName.substring(lastSlash + 1) : fullName;
    }
}
