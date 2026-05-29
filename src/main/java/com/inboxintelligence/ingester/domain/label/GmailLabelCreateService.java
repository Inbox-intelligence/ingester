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

    public void createLabel(String mailboxAddress, String labelName) {

        GmailMailbox mailbox = gmailMailboxService.findByEmailAddress(mailboxAddress)
                .orElseThrow(() -> new IllegalArgumentException("Mailbox not found: " + mailboxAddress));

        log.debug("Creating gmail label '{}' for {}", labelName, mailbox.getEmailAddress());

        Gmail gmail = gmailClientFactory.createUsingRefreshToken(mailbox.getRefreshToken());
        com.google.api.services.gmail.model.Label created = gmailApiClient.createLabel(gmail, labelName);

        if (created == null) {
            log.warn("Gmail returned no label for '{}' on mailbox {} — skipping local persist", labelName, mailbox.getEmailAddress());
            return;
        }

        Label saved = labelService.findByMailboxIdAndFullName(mailbox.getId(), created.getName())
                .orElseGet(() -> labelService.save(Label.builder()
                        .gmailMailboxId(mailbox.getId())
                        .displayName(LabelNameUtils.extractDisplayName(created.getName()))
                        .fullName(created.getName())
                        .build()));

        log.info("Persisted gmail label for {}: gmailId={} localId={} name='{}'", mailbox.getEmailAddress(), created.getId(), saved.getId(), created.getName());
    }
}
