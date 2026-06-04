package com.inboxintelligence.ingester.model;

import java.util.List;

public record GmailLabelApplyEvent(
        String mailboxAddress,
        String gmailLabelId,
        List<String> gmailMessageIds
) {
}
