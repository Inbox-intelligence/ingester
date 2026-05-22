package com.inboxintelligence.ingester.domain;

import com.inboxintelligence.persistence.model.entity.Label;
import com.inboxintelligence.persistence.service.LabelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailLabelProcessingService {

    private final LabelService labelService;

    private static String extractDisplayName(String fullName) {
        int lastSlash = fullName.lastIndexOf('/');
        return lastSlash >= 0 ? fullName.substring(lastSlash + 1) : fullName;
    }

    public Map<String, Object> processGmailLabels(Long mailboxId, Set<String> inboundLabelFullNames) {

        List<Label> labels = inboundLabelFullNames.stream()
                .map(fullName -> Label.builder()
                        .gmailMailboxId(mailboxId)
                        .displayName(extractDisplayName(fullName))
                        .fullName(fullName)
                        .build())
                .toList();

        labelService.flushAndFillLabels(mailboxId, labels);

        log.info("Mailbox [id={}]: gmail labels replaced — count={}", mailboxId, labels.size());
        return Map.of("inbound", labels.size());
    }
}
