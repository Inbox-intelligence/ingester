package com.inboxintelligence.ingester.domain;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.inboxintelligence.ingester.model.entity.EmailAttachment;
import com.inboxintelligence.ingester.model.entity.EmailContent;
import com.inboxintelligence.ingester.outbound.GmailApiClient;
import com.inboxintelligence.ingester.persistence.service.EmailAttachmentService;
import com.inboxintelligence.ingester.persistence.service.EmailContentService;
import com.inboxintelligence.ingester.persistence.storage.EmailStorageProvider;
import com.inboxintelligence.ingester.persistence.storage.EmailStorageProviderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

import static com.inboxintelligence.ingester.utils.Base64Util.decodeBase64Bytes;

/**
 * Orchestrates the full processing of a Gmail message: extract, store, save, and handle attachments.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GmailMessageProcessingService {

    private final GmailApiClient gmailApiClient;
    private final EmailContentService emailContentService;
    private final EmailAttachmentService emailAttachmentService;
    private final EmailStorageProviderFactory storageProviderFactory;

    public void process(Gmail gmail, Long mailboxId, Message message) {

        try {

            String messageId = message.getId();

            MessagePart messagePartPayload = message.getPayload();
            LocalDateTime messageDate = MimeContentUtil.parseInternalDate(message);

            var provider = storageProviderFactory.getProvider();
            String rawMessagePath = provider.storeRawMessage(mailboxId, messageId, message.toPrettyString());
            String bodyContentPath = provider.storeTextBody(mailboxId, messageId, MimeContentUtil.extractTextBody(messagePartPayload));
            String bodyHtmlContentPath = provider.storeHtmlBody(mailboxId, messageId, MimeContentUtil.extractHtmlBody(messagePartPayload));

            var emailContent = EmailContent.builder()
                    .gmailMailboxId(mailboxId)
                    .messageId(messageId)
                    .threadId(message.getThreadId())
                    .parentMessageId(MimeContentUtil.getHeader(message, "In-Reply-To"))
                    .rawMessagePath(rawMessagePath)
                    .subject(MimeContentUtil.getHeader(message, "Subject"))
                    .fromAddress(MimeContentUtil.getHeader(message, "From"))
                    .toAddress(MimeContentUtil.getHeader(message, "To"))
                    .ccAddress(MimeContentUtil.getHeader(message, "Cc"))
                    .bodyContentPath(bodyContentPath)
                    .bodyHtmlContentPath(bodyHtmlContentPath)
                    .sentAt(messageDate)
                    .receivedAt(messageDate)
                    .isProcessed(false)
                    .build();

            var savedEmail = emailContentService.save(emailContent);
            log.info("Email saved {}: {}", messageId, MimeContentUtil.getHeader(message, "Subject"));

            var list = MimeContentUtil.extractAttachmentMessageParts(messagePartPayload);
            list.forEach(part -> processAttachmentMessageParts(gmail, savedEmail, mailboxId, messageId, part));

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void processAttachmentMessageParts(Gmail gmail, EmailContent savedEmail, Long mailboxId, String messageId, MessagePart part) {

        try {
            byte[] data = fetchAttachmentData(gmail, messageId, part);

            if (data == null || data.length == 0) {
                log.warn("Empty attachment data for '{}' in message {}", part.getFilename(), messageId);
                return;
            }

            String fileName = StringUtils.hasText(part.getFilename()) ? part.getFilename() : "unnamed_" + System.currentTimeMillis();

            EmailStorageProvider provider = storageProviderFactory.getProvider();
            String storagePath = provider.storeAttachment(mailboxId, messageId, fileName, data);

            String contentId = null;
            boolean isInline = false;

            if (part.getHeaders() != null) {
                for (MessagePartHeader header : part.getHeaders()) {
                    if ("Content-ID".equalsIgnoreCase(header.getName())) {
                        contentId = header.getValue();
                    }
                    if ("Content-Disposition".equalsIgnoreCase(header.getName())) {
                        isInline = header.getValue().toLowerCase().startsWith("inline");
                    }
                }
            }

            EmailAttachment attachment = EmailAttachment.builder()
                    .emailContent(savedEmail)
                    .emailAttachmentId(part.getBody().getAttachmentId())
                    .fileName(fileName)
                    .mimeType(part.getMimeType())
                    .sizeInBytes((long) data.length)
                    .storagePath(storagePath)
                    .storageProvider(provider.providerName())
                    .contentId(contentId)
                    .isInline(isInline)
                    .isProcessed(false)
                    .build();

            emailAttachmentService.save(attachment);
            log.info("Attachment saved: '{}' ({}) for message {}", fileName, part.getMimeType(), messageId);

        } catch (Exception e) {
            log.warn("Failed to process attachment '{}' for message {}: {}", part.getFilename(), messageId, e.getMessage());
        }
    }


    private byte[] fetchAttachmentData(Gmail gmail, String messageId, MessagePart part) {

        MessagePartBody body = part.getBody();

        if (body == null) {
            return null;
        }

        if (StringUtils.hasText(body.getData())) {
            return decodeBase64Bytes(body.getData());
        } else if (StringUtils.hasText(body.getAttachmentId())) {

            MessagePartBody attachmentBody = gmailApiClient.fetchAttachment(gmail, messageId, body.getAttachmentId());

            if (attachmentBody != null && StringUtils.hasText(attachmentBody.getData())) {
                return decodeBase64Bytes(attachmentBody.getData());
            }
        }

        return null;
    }
}