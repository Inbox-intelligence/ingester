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
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailLabelFetchService {

    private static final String GMAIL_LABEL_TYPE_USER = "user";
    private static final String GMAIL_NOTES_LABEL = "Notes";

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

            log.debug("Label fetch started for {}", mailbox.getEmailAddress());

            Gmail gmail = gmailClientFactory.createUsingRefreshToken(mailbox.getRefreshToken());
            ListLabelsResponse response = gmailApiClient.listLabels(gmail);

            if (response == null || response.getLabels() == null || response.getLabels().isEmpty()) {
                log.warn("Label fetch for {}: Gmail returned no labels — refusing to apply diff", mailbox.getEmailAddress());
                return Map.of("success", false, "inbound", 0, "skipped", "empty-response");
            }

            Map<String, com.google.api.services.gmail.model.Label> gmailById = new LinkedHashMap<>();
            for (com.google.api.services.gmail.model.Label gmailLabel : response.getLabels()) {
                if (GMAIL_NOTES_LABEL.equalsIgnoreCase(gmailLabel.getName())) {
                    continue;
                }
                if (GMAIL_LABEL_TYPE_USER.equalsIgnoreCase(gmailLabel.getType())) {
                    gmailById.put(gmailLabel.getId(), gmailLabel);
                }
            }

            List<Label> localLabels = labelService.findByMailboxId(mailbox.getId());
            Map<String, Label> localByGmailId = new LinkedHashMap<>();
            for (Label local : localLabels) {
                if (StringUtils.hasText(local.getGmailLabelId())) {
                    localByGmailId.put(local.getGmailLabelId(), local);
                }
            }

            int created = 0;
            int renamed = 0;
            int unchanged = 0;

            for (Map.Entry<String, com.google.api.services.gmail.model.Label> entry : gmailById.entrySet()) {
                String gmailLabelId = entry.getKey();
                String gmailFullName = entry.getValue().getName();
                Label local = localByGmailId.get(gmailLabelId);

                if (local == null) {
                    Label fresh = Label.builder()
                            .gmailMailboxId(mailbox.getId())
                            .displayName(LabelNameUtils.extractDisplayName(gmailFullName))
                            .fullName(gmailFullName)
                            .gmailLabelId(gmailLabelId)
                            .build();
                    labelService.save(fresh);
                    created++;
                    log.debug("Inserted local label from Gmail [mailboxId={}, gmailLabelId={}, name='{}']", mailbox.getId(), gmailLabelId, gmailFullName);
                    continue;
                }

                if (!gmailFullName.equals(local.getFullName())) {
                    String oldFullName = local.getFullName();
                    local.setFullName(gmailFullName);
                    local.setDisplayName(LabelNameUtils.extractDisplayName(gmailFullName));
                    local.setReferenceEmbedding(null); // force taxonomy to re-embed on next run
                    labelService.save(local);
                    renamed++;
                    log.info("Label renamed in Gmail [mailboxId={}, gmailLabelId={}, oldName='{}', newName='{}'] — embedding cleared", mailbox.getId(), gmailLabelId, oldFullName, gmailFullName);
                    continue;
                }

                unchanged++;
            }

            Set<String> gmailIds = gmailById.keySet();
            List<String> orphanedNames = localLabels.stream()
                    .filter(l -> StringUtils.hasText(l.getGmailLabelId()))
                    .filter(l -> !gmailIds.contains(l.getGmailLabelId()))
                    .map(Label::getFullName)
                    .toList();

            if (!orphanedNames.isEmpty()) {
                log.warn("Local labels orphaned (deleted in Gmail) [mailboxId={}, count={}, fullNames={}] — leaving rows in place", mailbox.getId(), orphanedNames.size(), orphanedNames);
            }

            log.info("Label fetch done for {}: inbound={} created={} renamed={} unchanged={} orphaned={}", mailbox.getEmailAddress(), gmailById.size(), created, renamed, unchanged, orphanedNames.size());

            return Map.of(
                    "success", true,
                    "inbound", gmailById.size(),
                    "created", created,
                    "renamed", renamed,
                    "unchanged", unchanged,
                    "orphaned", orphanedNames.size()
            );
        } finally {
            lock.unlock();
        }
    }
}
