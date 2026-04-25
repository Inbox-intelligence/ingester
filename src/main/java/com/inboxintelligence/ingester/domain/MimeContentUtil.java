package com.inboxintelligence.ingester.domain;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import lombok.experimental.UtilityClass;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.inboxintelligence.ingester.utils.Base64Util.decodeBase64String;

@UtilityClass
public final class MimeContentUtil {

    public static String extractTextBody(MessagePart payload) {

        if (payload == null) {
            return null;
        }

        return findTextBody(payload);
    }

    public static String extractHtmlBody(MessagePart payload) {

        if (payload == null) {
            return null;
        }

        return findHtmlBody(payload);
    }

    public static List<MessagePart> extractAttachmentMessageParts(MessagePart payload) {

        List<MessagePart> attachments = new ArrayList<>();

        if (payload != null) {
            collectAttachments(payload, attachments);
        }

        return attachments;
    }

    public static String getHeader(Message message, String name) {

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

    public static Instant parseInternalDate(Message message) {

        if (message.getInternalDate() == null) {
            return null;
        }

        return Instant.ofEpochMilli(message.getInternalDate());
    }

    private static String findTextBody(MessagePart part) {

        String mime = part.getMimeType();

        if (mime == null) return null;

        if (mime.startsWith("multipart/") && part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                String result = findTextBody(child);
                if (result != null) return result;
            }
        }

        if ("text/plain".equalsIgnoreCase(mime) && part.getBody() != null) {
            return decodeBase64String(part.getBody().getData());
        }

        return null;
    }

    private static String findHtmlBody(MessagePart part) {

        String mime = part.getMimeType();

        if (mime == null) return null;

        if (mime.startsWith("multipart/") && part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                String result = findHtmlBody(child);
                if (result != null) return result;
            }
        }

        if ("text/html".equalsIgnoreCase(mime) && part.getBody() != null) {
            return decodeBase64String(part.getBody().getData());
        }

        return null;
    }

    private static void collectAttachments(MessagePart part, List<MessagePart> attachments) {

        String mime = part.getMimeType();

        if (mime == null) return;

        if (mime.startsWith("multipart/") && part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                collectAttachments(child, attachments);
            }
            return;
        }

        if (isAttachment(part)) {
            attachments.add(part);
        }
    }

    private static boolean isAttachment(MessagePart part) {

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
}
