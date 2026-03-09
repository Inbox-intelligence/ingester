package com.inboxintelligence.ingester.domain;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static com.inboxintelligence.ingester.utils.Base64Utils.decodeBase64String;

/**
 * Stateless extractor that walks MIME trees and pulls out body text,
 * HTML, and attachment parts.
 * <p>
 * Handles all common email structures:
 * <ul>
 *   <li>Simple text/html (no parts)</li>
 *   <li>multipart/alternative (text + html)</li>
 *   <li>multipart/mixed (body + attachments)</li>
 *   <li>multipart/related (html + inline images)</li>
 *   <li>Deeply nested combinations</li>
 *   <li>Calendar invites (text/calendar)</li>
 *   <li>Forwarded messages (message/rfc822)</li>
 * </ul>
 */
@Component
public class MimeContentExtractor {

    public ExtractedContent extract(MessagePart payload) {

        ExtractedContent result = new ExtractedContent();

        if (payload == null) {
            return result;
        }

        walkMimeParts(payload, result);
        return result;
    }

    public String getHeader(Message message, String name) {

        if (message.getPayload() == null || message.getPayload().getHeaders() == null) {
            return null;
        }

        return message.getPayload()
                .getHeaders()
                .stream()
                .filter(h -> name.equalsIgnoreCase(h.getName()))
                .map(MessagePartHeader::getValue)
                .findFirst()
                .orElse(null);
    }

    public LocalDateTime parseInternalDate(Message message) {

        if (message.getInternalDate() == null) {
            return null;
        }

        return Instant.ofEpochMilli(message.getInternalDate())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    // ── private helpers ─────────────────────────────────────────────────

    private void walkMimeParts(MessagePart part, ExtractedContent result) {

        String mimeType = part.getMimeType();

        if (mimeType == null) {
            return;
        }

        String mimeTypeLower = mimeType.toLowerCase();

        // Multipart container — recurse into children
        if (mimeTypeLower.startsWith("multipart/")) {
            if (part.getParts() != null) {
                for (MessagePart child : part.getParts()) {
                    walkMimeParts(child, result);
                }
            }
            return;
        }

        // Attachment (has filename or Content-Disposition: attachment)
        if (isAttachment(part)) {
            result.attachmentParts().add(part);
            return;
        }

        // Leaf part — extract body content
        switch (mimeTypeLower) {
            case "text/plain" -> {
                if (result.textBody() == null && part.getBody() != null) {
                    result.setTextBody(decodeBase64String(part.getBody().getData()));
                }
            }
            case "text/html" -> {
                if (result.htmlBody() == null && part.getBody() != null) {
                    result.setHtmlBody(decodeBase64String(part.getBody().getData()));
                }
            }
            default -> {
                // Non-text leaf parts (e.g., text/calendar, image/*, message/rfc822)
                result.attachmentParts().add(part);
            }
        }
    }

    private boolean isAttachment(MessagePart part) {

        if (StringUtils.hasText(part.getFilename())) {
            return true;
        }

        if (part.getHeaders() != null) {
            for (MessagePartHeader header : part.getHeaders()) {
                if ("Content-Disposition".equalsIgnoreCase(header.getName())) {
                    String value = header.getValue().toLowerCase();
                    if (value.startsWith("attachment")) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    // ── Data holder ─────────────────────────────────────────────────────

    public static class ExtractedContent {

        private String textBody;
        private String htmlBody;
        private final List<MessagePart> attachmentParts = new ArrayList<>();

        public String textBody() { return textBody; }
        public String htmlBody() { return htmlBody; }
        public List<MessagePart> attachmentParts() { return attachmentParts; }

        public void setTextBody(String textBody) { this.textBody = textBody; }
        public void setHtmlBody(String htmlBody) { this.htmlBody = htmlBody; }
    }
}
