package com.inboxintelligence.ingester.domain.label;

import com.inboxintelligence.persistence.model.enums.Category;
import com.inboxintelligence.persistence.model.enums.Importance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

// Add-only mapping from (Importance, Category) to Gmail label IDs, applied in
// one batchModify call. Null on either field skips it. LOW is a no-op — this
// project never removes labels.
@Slf4j
@Service
@RequiredArgsConstructor
public class GmailEnrichmentApplyService {

    private final GmailLabelPublishService gmailLabelPublishService;

    public Map<String, Object> applyEnrichment(String mailboxAddress,
                                               String messageId,
                                               String importance,
                                               String category) {

        Importance imp = parseEnum(Importance.class, importance);
        Category cat = parseEnum(Category.class, category);

        List<String> labels = Stream.concat(labelsFor(imp).stream(), labelsFor(cat).stream())
                .distinct()
                .toList();

        if (labels.isEmpty()) {
            log.debug("Enrichment no-op for {} message={} (importance={}, category={})", mailboxAddress, messageId, imp, cat);
            return Map.of("modified", 0, "added", List.of());
        }

        log.debug("Applying enrichment to {} message={} (importance={}, category={}, add={})", mailboxAddress, messageId, imp, cat, labels);

        Map<String, Object> result = gmailLabelPublishService.applyLabelsToMessages(mailboxAddress, List.of(messageId), labels, List.of());

        return Map.of("modified", result.getOrDefault("modified", 0), "added", labels);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        return (value == null || value.isBlank()) ? null : Enum.valueOf(type, value.trim().toUpperCase());
    }

    private static List<String> labelsFor(Importance importance) {
        if (importance == null) return List.of();
        return switch (importance) {
            case HIGH   -> List.of("IMPORTANT", "STARRED");
            case MEDIUM -> List.of("IMPORTANT");
            case LOW    -> List.of();
        };
    }

    private static List<String> labelsFor(Category category) {
        if (category == null) return List.of();
        return List.of(switch (category) {
            case PRIMARY    -> "CATEGORY_PERSONAL";
            case SOCIAL     -> "CATEGORY_SOCIAL";
            case PROMOTIONS -> "CATEGORY_PROMOTIONS";
            case UPDATES    -> "CATEGORY_UPDATES";
            case FORUMS     -> "CATEGORY_FORUMS";
        });
    }
}
