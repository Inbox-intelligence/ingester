package com.inboxintelligence.ingester.domain.label;

import com.google.api.services.gmail.Gmail;
import com.inboxintelligence.ingester.outbound.GmailApiClient;
import com.inboxintelligence.ingester.outbound.GmailClientFactory;
import com.inboxintelligence.persistence.model.entity.GmailMailbox;
import com.inboxintelligence.persistence.model.entity.Label;
import com.inboxintelligence.persistence.service.GmailMailboxService;
import com.inboxintelligence.persistence.service.LabelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailLabelCreateService {

    private final GmailClientFactory gmailClientFactory;
    private final GmailMailboxService gmailMailboxService;
    private final GmailApiClient gmailApiClient;
    private final LabelService labelService;

    private static String extractDisplayName(String fullName) {
        int lastSlash = fullName.lastIndexOf('/');
        return lastSlash >= 0 ? fullName.substring(lastSlash + 1) : fullName;
    }

    public void createLabel(String mailboxAddress, String labelName) {

        GmailMailbox mailbox = gmailMailboxService.findByEmailAddress(mailboxAddress)
                .orElseThrow(() -> new IllegalArgumentException("Mailbox not found: " + mailboxAddress));

        log.info("Creating gmail label '{}' for {}", labelName, mailbox.getEmailAddress());

        Gmail gmail = gmailClientFactory.createUsingRefreshToken(mailbox.getRefreshToken());
        com.google.api.services.gmail.model.Label created = gmailApiClient.createLabel(gmail, labelName);

        if (created != null) {

            Label saved = labelService.save(Label.builder()
                    .gmailMailboxId(mailbox.getId())
                    .displayName(extractDisplayName(created.getName()))
                    .fullName(created.getName())
                    .build());

            log.info("Created gmail label for {}: gmailId={} localId={} name='{}'", mailbox.getEmailAddress(), created.getId(), saved.getId(), created.getName());
        }
    }
}
