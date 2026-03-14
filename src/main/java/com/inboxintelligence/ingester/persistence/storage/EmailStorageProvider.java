package com.inboxintelligence.ingester.persistence.storage;

/** Strategy interface for email content and attachment storage. */
public interface EmailStorageProvider {

    String storeRawMessage(Long mailboxId, String messageId, String rawMessage);

    String storeTextBody(Long mailboxId, String messageId, String textBody);

    String storeHtmlBody(Long mailboxId, String messageId, String htmlBody);

    String storeAttachment(Long mailboxId, String messageId, String fileName, byte[] data);

    String providerName();
}
