package com.inboxintelligence.ingester.domain;

import com.inboxintelligence.ingester.model.ResolvedAttachment;
import com.inboxintelligence.ingester.model.StoredEmailContentPaths;
import com.inboxintelligence.ingester.model.entity.EmailAttachment;
import com.inboxintelligence.ingester.model.entity.EmailContent;
import com.inboxintelligence.ingester.persistence.service.EmailAttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Orchestrates email content and attachment persistence.
 * <p>
 * Delegates actual file storage to the active {@link EmailStorageProvider}
 * (selected by {@link EmailStorageProviderFactory}) and handles
 * attachment metadata persistence in the database.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailContentStorageService {

    private final EmailAttachmentService emailAttachmentService;
    private final EmailStorageProviderFactory storageProviderFactory;

    public StoredEmailContentPaths storeEmailContent(Long mailboxId, String messageId,
                                                      String rawMessage, String textBody, String htmlBody)
            throws IOException {

        return storageProviderFactory.getProvider()
                .storeEmailContent(mailboxId, messageId, rawMessage, textBody, htmlBody);
    }

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

        EmailStorageProvider provider = storageProviderFactory.getProvider();

        Path storagePath = provider.storeAttachment(mailboxId, messageId, resolved.fileName(), resolved.data());

        EmailAttachment attachment = EmailAttachment.builder()
                .emailContent(savedEmail)
                .emailAttachmentId(resolved.attachmentId())
                .fileName(resolved.fileName())
                .mimeType(resolved.mimeType())
                .sizeInBytes(resolved.sizeInBytes())
                .storagePath(storagePath.toString())
                .storageProvider(provider.providerName())
                .contentId(resolved.contentId())
                .isInline(resolved.isInline())
                .isProcessed(false)
                .build();

        emailAttachmentService.save(attachment);

        log.info("Attachment saved: '{}' ({}) for message {}", resolved.fileName(), resolved.mimeType(), messageId);
    }
}
