package com.inboxintelligence.ingester.persistence.storage;

import org.springframework.stereotype.Component;

@Component
public class S3EmailStorageProvider implements EmailStorageProvider {

    @Override
    public String storeRawMessage(Long mailboxId, String messageId, String rawMessage) {
        throw new UnsupportedOperationException("S3EmailStorageProvider is not implemented");
    }

    @Override
    public String storeTextBody(Long mailboxId, String messageId, String textBody) {
        throw new UnsupportedOperationException("S3EmailStorageProvider is not implemented");
    }

    @Override
    public String storeHtmlBody(Long mailboxId, String messageId, String htmlBody) {
        throw new UnsupportedOperationException("S3EmailStorageProvider is not implemented");
    }

    @Override
    public String storeAttachment(Long mailboxId, String messageId, String fileName, byte[] data) {
        throw new UnsupportedOperationException("S3EmailStorageProvider is not implemented");
    }

    @Override
    public String providerName() {
        return "s3";
    }
}
