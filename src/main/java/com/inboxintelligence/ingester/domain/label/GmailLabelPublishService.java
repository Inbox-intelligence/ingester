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
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailLabelPublishService {

    private static final int BATCH_MODIFY_MAX_IDS = 1000;
    private static final String GMAIL_LABEL_TYPE_USER = "user";

    private final GmailClientFactory gmailClientFactory;
    private final GmailMailboxService gmailMailboxService;
    private final GmailApiClient gmailApiClient;
    private final LabelService labelService;

    public Map<String, Object> publishLabels(String mailboxAddress) {

        GmailMailbox mailbox = gmailMailboxService.findByEmailAddress(mailboxAddress)
                .orElseThrow(() -> new IllegalArgumentException("Mailbox not found: " + mailboxAddress));

        List<Label> localLabels = labelService.findByMailboxId(mailbox.getId());
        log.debug("Publishing {} local labels to Gmail for {}", localLabels.size(), mailbox.getEmailAddress());

        Gmail gmail = gmailClientFactory.createUsingRefreshToken(mailbox.getRefreshToken());

        ListLabelsResponse existing = gmailApiClient.listLabels(gmail);
        Set<String> existingNames = (existing == null || existing.getLabels() == null)
                ? Set.of()
                : existing.getLabels().stream()
                        .map(com.google.api.services.gmail.model.Label::getName)
                        .collect(Collectors.toSet());

        int created = 0;
        int skipped = 0;
        int failed = 0;

        for (Label local : localLabels) {
            String name = local.getFullName();
            if (existingNames.contains(name)) {
                skipped++;
                continue;
            }
            try {
                com.google.api.services.gmail.model.Label gmailLabel = gmailApiClient.createLabel(gmail, name);
                if (gmailLabel == null) {
                    failed++;
                    log.warn("Gmail returned null when creating label '{}' for {}", name, mailbox.getEmailAddress());
                } else {
                    created++;
                    log.debug("Created gmail label '{}' for {}", name, mailbox.getEmailAddress());
                }
            } catch (Exception e) {
                failed++;
                log.warn("Failed creating label '{}' for {}: {}", name, mailbox.getEmailAddress(), e.getMessage());
            }
        }

        log.info("Label publish done for {}: created={} skipped={} failed={}", mailbox.getEmailAddress(), created, skipped, failed);
        return Map.of(
                "success", failed == 0,
                "created", created,
                "skipped", skipped,
                "failed", failed,
                "total", localLabels.size()
        );
    }

    public Map<String, Object> applyLabelsToMessages(String mailboxAddress,
                                                     List<String> messageIds,
                                                     List<String> addLabelIds,
                                                     List<String> removeLabelIds) {

        GmailMailbox mailbox = gmailMailboxService.findByEmailAddress(mailboxAddress)
                .orElseThrow(() -> new IllegalArgumentException("Mailbox not found: " + mailboxAddress));

        if (CollectionUtils.isEmpty(messageIds)) {
            log.debug("No message ids supplied for {} — nothing to label", mailbox.getEmailAddress());
            return Map.of("modified", 0);
        }

        if (CollectionUtils.isEmpty(addLabelIds) && CollectionUtils.isEmpty(removeLabelIds)) {
            log.debug("No add/remove label ids for {} — nothing to label", mailbox.getEmailAddress());
            return Map.of("modified", 0);
        }

        log.debug("Labeling {} message(s) for {}: add={} remove={}", messageIds.size(), mailbox.getEmailAddress(), addLabelIds, removeLabelIds);

        Gmail gmail = gmailClientFactory.createUsingRefreshToken(mailbox.getRefreshToken());

        int modified = 0;
        for (int i = 0; i < messageIds.size(); i += BATCH_MODIFY_MAX_IDS) {
            List<String> chunk = messageIds.subList(i, Math.min(i + BATCH_MODIFY_MAX_IDS, messageIds.size()));
            gmailApiClient.batchModifyMessages(gmail, chunk, addLabelIds, removeLabelIds);
            modified += chunk.size();
            log.debug("Modified {}/{} message(s) for {}", modified, messageIds.size(), mailbox.getEmailAddress());
        }

        log.debug("Label apply done for {}: modified={}", mailbox.getEmailAddress(), modified);
        return Map.of("modified", modified);
    }

    public Map<String, Object> deleteAllGmailLabels(String mailboxAddress) {

        GmailMailbox mailbox = gmailMailboxService.findByEmailAddress(mailboxAddress)
                .orElseThrow(() -> new IllegalArgumentException("Mailbox not found: " + mailboxAddress));

        Gmail gmail = gmailClientFactory.createUsingRefreshToken(mailbox.getRefreshToken());
        ListLabelsResponse response = gmailApiClient.listLabels(gmail);

        if (response == null || response.getLabels() == null) {
            return Map.of("deleted", 0, "failed", 0);
        }

        int deleted = 0;
        int failed = 0;
        for (com.google.api.services.gmail.model.Label label : response.getLabels()) {
            if (!GMAIL_LABEL_TYPE_USER.equalsIgnoreCase(label.getType())) {
                continue;
            }
            try {
                gmailApiClient.deleteLabel(gmail, label.getId());
                deleted++;
                log.debug("Deleted gmail label id={} name='{}'", label.getId(), label.getName());
            } catch (Exception e) {
                failed++;
                log.warn("Failed deleting gmail label id={} name='{}': {}", label.getId(), label.getName(), e.getMessage());
            }
        }

        log.info("Delete-all gmail labels done for {}: deleted={} failed={}", mailboxAddress, deleted, failed);
        return Map.of("deleted", deleted, "failed", failed);
    }
}
