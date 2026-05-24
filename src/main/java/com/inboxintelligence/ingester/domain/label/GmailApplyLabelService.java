package com.inboxintelligence.ingester.domain.label;

import com.google.api.services.gmail.Gmail;
import com.inboxintelligence.ingester.outbound.GmailApiClient;
import com.inboxintelligence.ingester.outbound.GmailClientFactory;
import com.inboxintelligence.persistence.model.entity.GmailMailbox;
import com.inboxintelligence.persistence.service.GmailMailboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailApplyLabelService {

    private static final int BATCH_MODIFY_MAX_IDS = 1000;

    private final GmailClientFactory gmailClientFactory;
    private final GmailMailboxService gmailMailboxService;
    private final GmailApiClient gmailApiClient;

    public Map<String, Object> applyLabelsToMessages(String mailboxAddress,
                                                     List<String> messageIds,
                                                     List<String> addLabelIds,
                                                     List<String> removeLabelIds) {

        GmailMailbox mailbox = gmailMailboxService.findByEmailAddress(mailboxAddress)
                .orElseThrow(() -> new IllegalArgumentException("Mailbox not found: " + mailboxAddress));

        if (CollectionUtils.isEmpty(messageIds)) {
            log.info("No message ids supplied for {} — nothing to label", mailbox.getEmailAddress());
            return Map.of("modified", 0);
        }

        if (CollectionUtils.isEmpty(addLabelIds) && CollectionUtils.isEmpty(removeLabelIds)) {
            log.info("No add/remove label ids for {} — nothing to label", mailbox.getEmailAddress());
            return Map.of("modified", 0);
        }

        log.info("Labeling {} message(s) for {}: add={} remove={}", messageIds.size(), mailbox.getEmailAddress(), addLabelIds, removeLabelIds);

        Gmail gmail = gmailClientFactory.createUsingRefreshToken(mailbox.getRefreshToken());

        int modified = 0;
        for (int i = 0; i < messageIds.size(); i += BATCH_MODIFY_MAX_IDS) {
            List<String> chunk = messageIds.subList(i, Math.min(i + BATCH_MODIFY_MAX_IDS, messageIds.size()));
            gmailApiClient.batchModifyMessages(gmail, chunk, addLabelIds, removeLabelIds);
            modified += chunk.size();
            log.debug("Modified {}/{} message(s) for {}", modified, messageIds.size(), mailbox.getEmailAddress());
        }

        log.info("Label apply done for {}: modified={}", mailbox.getEmailAddress(), modified);
        return Map.of("modified", modified);
    }
}
