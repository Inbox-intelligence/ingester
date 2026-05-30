package com.inboxintelligence.ingester.domain.label;

import com.inboxintelligence.persistence.model.enums.Importance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

// HIGH → IMPORTANT + STARRED, MEDIUM → IMPORTANT, LOW → no-op.
@Slf4j
@Service
@RequiredArgsConstructor
public class GmailImportanceService {

    private static final String GMAIL_LABEL_IMPORTANT = "IMPORTANT";
    private static final String GMAIL_LABEL_STARRED = "STARRED";

    private final GmailLabelPublishService gmailLabelPublishService;

    public Map<String, Object> applyImportance(String mailboxAddress, String messageId, Importance importance) {

        if (importance == null || importance == Importance.LOW) {
            log.debug("Importance {} for {} message={} — no Gmail change", importance, mailboxAddress, messageId);
            return Map.of("modified", 0, "applied", List.of());
        }

        List<String> labelsToAdd = switch (importance) {
            case HIGH   -> List.of(GMAIL_LABEL_IMPORTANT, GMAIL_LABEL_STARRED);
            case MEDIUM -> List.of(GMAIL_LABEL_IMPORTANT);
            case LOW    -> List.of();
        };

        log.info("Applying importance={} to {} message={} (labels={})",
                importance, mailboxAddress, messageId, labelsToAdd);

        Map<String, Object> result = gmailLabelPublishService.applyLabelsToMessages(
                mailboxAddress,
                List.of(messageId),
                labelsToAdd,
                List.of()
        );

        return Map.of(
                "modified", result.getOrDefault("modified", 0),
                "applied", labelsToAdd
        );
    }
}
