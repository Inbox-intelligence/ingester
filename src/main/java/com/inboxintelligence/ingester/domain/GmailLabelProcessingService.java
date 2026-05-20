package com.inboxintelligence.ingester.domain;

import com.inboxintelligence.persistence.model.entity.Label;
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
public class GmailLabelProcessingService {

    private final LabelService labelService;

    public Map<String, Object> processGmailLabels(Long mailboxId, Set<String> inboundLabelFullNames) {

        if (inboundLabelFullNames.isEmpty()) {
            log.info("Mailbox [id={}]: gmail returned no user labels — nothing to do", mailboxId);
            return Map.of("inbound", 0, "created", 0, "deleted", 0);
        }

        Set<String> existingFullNames = labelService.findByMailboxId(mailboxId).stream()
                .map(Label::getFullName)
                .collect(Collectors.toSet());

        int created = createNewLabels(mailboxId, inboundLabelFullNames, existingFullNames);
        int deleted = deleteMissingLabels(mailboxId, inboundLabelFullNames, existingFullNames);

        log.info("Mailbox [id={}]: gmail labels processed — inbound={} created={} deleted={}",
                mailboxId, inboundLabelFullNames.size(), created, deleted);

        return Map.of("inbound", inboundLabelFullNames.size(), "created", created, "deleted", deleted);
    }

    private int deleteMissingLabels(Long mailboxId, Set<String> inboundLabelFullNames, Set<String> existingFullNames) {

        List<String> toDelete = existingFullNames.stream()
                .filter(fullName -> !inboundLabelFullNames.contains(fullName))
                .toList();

        if (!toDelete.isEmpty()) {
            labelService.deleteByMailboxIdAndFullNames(mailboxId, toDelete);
        }

        return toDelete.size();
    }

    private int createNewLabels(Long mailboxId, Set<String> inboundLabelFullNames, Set<String> existingFullNames) {

        List<Label> toCreate = inboundLabelFullNames.stream()
                .filter(fullName -> !existingFullNames.contains(fullName))
                .map(fullName -> Label.builder()
                        .gmailMailboxId(mailboxId)
                        .displayName(extractDisplayName(fullName))
                        .fullName(fullName)
                        .build())
                .toList();

        if (!toCreate.isEmpty()) {
            labelService.saveAll(toCreate);
        }

        return toCreate.size();
    }

    private static String extractDisplayName(String fullName) {
        int lastSlash = fullName.lastIndexOf('/');
        return lastSlash >= 0 ? fullName.substring(lastSlash + 1) : fullName;
    }
}
