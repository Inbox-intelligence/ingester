package com.inboxintelligence.ingester.domain;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.inboxintelligence.ingester.outbound.EmailEventPublisher;
import com.inboxintelligence.ingester.outbound.GmailApiClient;
import com.inboxintelligence.persistence.model.EmailOrigin;
import com.inboxintelligence.persistence.model.entity.EmailAttachment;
import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.service.EmailAttachmentService;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.storage.EmailStorageProvider;
import com.inboxintelligence.persistence.storage.EmailStorageProviderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static com.inboxintelligence.ingester.utils.Base64Util.decodeBase64Bytes;
import static com.inboxintelligence.persistence.model.ProcessedStatus.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class GmailMessageProcessingService {

    private final GmailApiClient gmailApiClient;
    private final EmailContentService emailContentService;
    private final EmailAttachmentService emailAttachmentService;
    private final EmailStorageProviderFactory storageProviderFactory;
    private final EmailEventPublisher emailEventPublisher;

    public void process(Gmail gmail, Long mailboxId, Message message, EmailOrigin origin) {

        EmailContent savedEmail = null;
        try {
            log.debug("Processing message {} for mailbox {}", message.getId(), mailboxId);

            savedEmail = saveEmailContentEntity(mailboxId, message, origin);
            saveEmailContentInStorage(mailboxId, message, savedEmail);
            saveEmailAttachment(gmail, mailboxId, message, savedEmail);
            emailEventPublisher.publishEmailProcessed(savedEmail);

        } catch (Exception e) {
            log.error("Failed to process message {} for mailbox {}", message.getId(), mailboxId, e);
            if (savedEmail != null) {
                emailContentService.updateStatusAndNote(savedEmail, INGESTION_FAILED, e.getMessage());
            }
            throw new RuntimeException(e);
        }
    }

    private EmailContent saveEmailContentEntity(Long mailboxId, Message message, EmailOrigin origin) {

        String messageId = message.getId();
        Instant messageDate = MimeContentUtil.parseInternalDate(message);

        EmailContent emailContent = EmailContent.builder()
                .gmailMailboxId(mailboxId)
                .messageId(messageId)
                .threadId(message.getThreadId())
                .parentMessageId(MimeContentUtil.getHeader(message, "In-Reply-To"))
                .subject(MimeContentUtil.getHeader(message, "Subject"))
                .fromAddress(MimeContentUtil.getHeader(message, "From"))
                .toAddress(MimeContentUtil.getHeader(message, "To"))
                .ccAddress(MimeContentUtil.getHeader(message, "Cc"))
                .sentAt(messageDate)
                .receivedAt(messageDate)
                .origin(origin)
                .processedStatus(EMAIL_RECEIVED)
                .build();

        EmailContent savedEmail = emailContentService.save(emailContent);
        log.info("Email saved {}: {}", messageId, MimeContentUtil.getHeader(message, "Subject"));
        return savedEmail;
    }

    private void saveEmailContentInStorage(Long mailboxId, Message message, EmailContent savedEmail) throws IOException {

        String messageId = message.getId();
        MessagePart messagePartPayload = message.getPayload();

        EmailStorageProvider provider = storageProviderFactory.getProvider();

        try{
            savedEmail.setBodyContentPath(provider.writeContent(mailboxId, messageId, "body.txt", MimeContentUtil.extractTextBody(messagePartPayload)));
            savedEmail.setBodyHtmlContentPath(provider.writeContent(mailboxId, messageId, "body.html", MimeContentUtil.extractHtmlBody(messagePartPayload)));
        } catch (Exception e) {
            savedEmail.setRawMessagePath(provider.writeContent(mailboxId, messageId, "raw_message.json", message.toPrettyString()));
            throw e;
        }

        savedEmail.setProcessedStatus(CONTENT_SAVED);
        emailContentService.save(savedEmail);

        log.info("Content saved for message {}", messageId);
    }

    private void saveEmailAttachment(Gmail gmail, Long mailboxId, Message message, EmailContent savedEmail) {

        String messageId = message.getId();
        MessagePart messagePartPayload = message.getPayload();

        List<MessagePart> list = MimeContentUtil.extractAttachmentMessageParts(messagePartPayload);
        log.debug("Found {} attachments for message {}", list.size(), messageId);

        list.forEach(part -> processAttachmentMessageParts(gmail, savedEmail, mailboxId, messageId, part));

        savedEmail.setProcessedStatus(ATTACHMENT_SAVED);
        emailContentService.save(savedEmail);

        log.info("Attachments saved for message {}", messageId);
    }

    private void processAttachmentMessageParts(Gmail gmail, EmailContent savedEmail, Long mailboxId, String messageId, MessagePart part) {

        try {

            byte[] data = fetchAttachmentData(gmail, messageId, part);

            if (data != null) {

                EmailStorageProvider provider = storageProviderFactory.getProvider();
                String fileName = StringUtils.hasText(part.getFilename()) ? part.getFilename() : "unnamed_" + System.currentTimeMillis();
                String storagePath = provider.writeBytes(mailboxId, messageId, "attachment", fileName, data);
                saveEmailAttachmentEntity(savedEmail, part, fileName, storagePath, data.length, provider.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.warn("Failed to process attachment '{}' for message {}: {}", part.getFilename(), messageId, e.getMessage());
        }
    }

    private byte[] fetchAttachmentData(Gmail gmail, String messageId, MessagePart part) {

        MessagePartBody body = part.getBody();

        if (body == null) {
            log.warn("Empty attachment data for '{}' in message {}", part.getFilename(), messageId);
            return null;
        }

        if (StringUtils.hasText(body.getData())) {
            log.debug("Decoding inline attachment data for '{}' in message {}", part.getFilename(), messageId);
            return decodeBase64Bytes(body.getData());
        }

        if (StringUtils.hasText(body.getAttachmentId())) {

            log.debug("Fetching remote attachment '{}' (attachmentId={}) for message {}", part.getFilename(), body.getAttachmentId(), messageId);
            MessagePartBody attachmentBody = gmailApiClient.fetchAttachment(gmail, messageId, body.getAttachmentId());

            if (attachmentBody != null && StringUtils.hasText(attachmentBody.getData())) {
                return decodeBase64Bytes(attachmentBody.getData());
            }
        }

        log.warn("Empty attachment data for '{}' in message {}", part.getFilename(), messageId);
        return null;
    }

    private void saveEmailAttachmentEntity(EmailContent savedEmail, MessagePart part, String fileName, String storagePath, long sizeInBytes, String providerName) {

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
                .emailContentId(savedEmail.getId())
                .emailAttachmentId(part.getBody() != null ? part.getBody().getAttachmentId() : null)
                .fileName(fileName)
                .mimeType(part.getMimeType())
                .sizeInBytes(sizeInBytes)
                .storagePath(storagePath)
                .storageProvider(providerName)
                .contentId(contentId)
                .isInline(isInline)
                .build();

        emailAttachmentService.save(attachment);
        log.info("Attachment saved: '{}' ({}) for message {}", fileName, part.getMimeType(), savedEmail.getMessageId());
    }
}
