package com.inboxintelligence.ingester.domain;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.inboxintelligence.ingester.common.AttachmentStorageProperties;
import com.inboxintelligence.ingester.model.entity.EmailAttachment;
import com.inboxintelligence.ingester.model.entity.EmailContent;
import com.inboxintelligence.ingester.persistence.service.EmailAttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.inboxintelligence.ingester.utils.Base64Utils.decodeBase64Bytes;

/**
 * Downloads attachment data from Gmail and persists it to local storage.
 * <p>
 * If you later migrate to S3 or GCS, this is the only class that changes.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AttachmentStorageService {

    private final GmailApiClient gmailApiClient;
    private final EmailAttachmentService emailAttachmentService;
    private final AttachmentStorageProperties attachmentStorageProperties;

    public void processAttachments(Gmail gmail, Long mailboxId, EmailContent savedEmail,
                                   String messageId, List<MessagePart> attachmentParts) {

        if (CollectionUtils.isEmpty(attachmentParts)) {
            return;
        }

        for (MessagePart part : attachmentParts) {
            try {
                processOneAttachment(gmail, mailboxId, savedEmail, messageId, part);
            } catch (Exception e) {
                log.error("Failed to process attachment '{}' for message {}: {}",
                        part.getFilename(), messageId, e.getMessage());
            }
        }
    }

    // ── private helpers ─────────────────────────────────────────────────

    private void processOneAttachment(Gmail gmail, Long mailboxId, EmailContent savedEmail,
                                      String messageId, MessagePart part) throws IOException {

        byte[] data = getAttachmentData(gmail, messageId, part);

        if (data == null || data.length == 0) {
            log.warn("Empty attachment data for '{}' in message {}", part.getFilename(), messageId);
            return;
        }

        String fileName = StringUtils.hasText(part.getFilename())
                ? part.getFilename()
                : "unnamed_" + System.currentTimeMillis();

        Path storagePath = saveToLocalStorage(mailboxId, messageId, fileName, data);

        boolean isInline = isInlinePart(part);

        String attachmentId = (part.getBody() != null) ? part.getBody().getAttachmentId() : null;
        long sizeInBytes = (part.getBody() != null && part.getBody().getSize() != null)
                ? part.getBody().getSize()
                : data.length;

        EmailAttachment attachment = EmailAttachment.builder()
                .emailContent(savedEmail)
                .emailAttachmentId(attachmentId)
                .fileName(fileName)
                .mimeType(part.getMimeType())
                .sizeInBytes(sizeInBytes)
                .storagePath(storagePath.toString())
                .storageProvider("LOCAL")
                .isInline(isInline)
                .isProcessed(false)
                .build();

        emailAttachmentService.save(attachment);

        log.info("Attachment saved: '{}' ({}) for message {}", fileName, part.getMimeType(), messageId);
    }

    private byte[] getAttachmentData(Gmail gmail, String messageId, MessagePart part) throws IOException {

        MessagePartBody body = part.getBody();

        if (body == null) {
            return null;
        }

        // Small attachments have data inline
        if (StringUtils.hasText(body.getData())) {
            return decodeBase64Bytes(body.getData());
        }

        // Large attachments need a separate API call
        if (StringUtils.hasText(body.getAttachmentId())) {
            MessagePartBody attachmentBody = gmailApiClient.fetchAttachment(gmail, messageId, body.getAttachmentId());
            if (attachmentBody != null && StringUtils.hasText(attachmentBody.getData())) {
                return decodeBase64Bytes(attachmentBody.getData());
            }
        }

        return null;
    }

    private Path saveToLocalStorage(Long mailboxId, String messageId, String fileName, byte[] data)
            throws IOException {

        Path dir = Path.of(attachmentStorageProperties.basePath(), String.valueOf(mailboxId), messageId);
        Files.createDirectories(dir);

        // Sanitize filename to avoid path traversal
        String safeFileName = Path.of(fileName).getFileName().toString();
        Path filePath = dir.resolve(safeFileName);

        // Handle duplicate filenames
        if (Files.exists(filePath)) {
            String name = safeFileName;
            String ext = "";
            int dotIdx = safeFileName.lastIndexOf('.');
            if (dotIdx > 0) {
                name = safeFileName.substring(0, dotIdx);
                ext = safeFileName.substring(dotIdx);
            }
            int counter = 1;
            while (Files.exists(filePath)) {
                filePath = dir.resolve(name + "_" + counter + ext);
                counter++;
            }
        }

        Files.write(filePath, data);
        return filePath;
    }

    private boolean isInlinePart(MessagePart part) {

        if (part.getHeaders() == null) {
            return false;
        }

        for (MessagePartHeader header : part.getHeaders()) {
            if ("Content-Disposition".equalsIgnoreCase(header.getName())) {
                return header.getValue().toLowerCase().startsWith("inline");
            }
        }

        return false;
    }
}
