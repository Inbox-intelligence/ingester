package com.inboxintelligence.ingester.domain;

import com.inboxintelligence.ingester.config.EmailContentStorageProperties;
import com.inboxintelligence.ingester.model.entity.EmailAttachment;
import com.inboxintelligence.ingester.model.entity.EmailContent;
import com.inboxintelligence.ingester.persistence.service.EmailAttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Persists pre-resolved attachment data to local storage.
 * <p>
 * If you later migrate to S3 or GCS, this is the only class that changes.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailContentStorageService {

    private final EmailAttachmentService emailAttachmentService;
    private final EmailContentStorageProperties emailContentStorageProperties;

    public record ResolvedAttachment(
            String fileName,
            String mimeType,
            String attachmentId,
            long sizeInBytes,
            byte[] data,
            boolean isInline
    ) {}

    public void processAttachments(Long mailboxId, EmailContent savedEmail,
                                   String messageId, List<ResolvedAttachment> resolvedAttachments) {

        if (CollectionUtils.isEmpty(resolvedAttachments)) {
            return;
        }

        for (ResolvedAttachment attachment : resolvedAttachments) {
            try {
                storeOneAttachment(mailboxId, savedEmail, messageId, attachment);
            } catch (Exception e) {
                log.error("Failed to store attachment '{}' for message {}: {}",
                        attachment.fileName(), messageId, e.getMessage());
            }
        }
    }

    // ── private helpers ─────────────────────────────────────────────────

    private void storeOneAttachment(Long mailboxId, EmailContent savedEmail,
                                    String messageId, ResolvedAttachment resolved) throws IOException {

        Path storagePath = saveToLocalStorage(mailboxId, messageId, resolved.fileName(), resolved.data());

        EmailAttachment attachment = EmailAttachment.builder()
                .emailContent(savedEmail)
                .emailAttachmentId(resolved.attachmentId())
                .fileName(resolved.fileName())
                .mimeType(resolved.mimeType())
                .sizeInBytes(resolved.sizeInBytes())
                .storagePath(storagePath.toString())
                .storageProvider("LOCAL")
                .isInline(resolved.isInline())
                .isProcessed(false)
                .build();

        emailAttachmentService.save(attachment);

        log.info("Attachment saved: '{}' ({}) for message {}", resolved.fileName(), resolved.mimeType(), messageId);
    }

    private Path saveToLocalStorage(Long mailboxId, String messageId, String fileName, byte[] data)
            throws IOException {

        Path dir = Path.of(emailContentStorageProperties.attachmentPath(), String.valueOf(mailboxId), messageId);
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

}
