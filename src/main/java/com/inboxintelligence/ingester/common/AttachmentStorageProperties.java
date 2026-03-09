package com.inboxintelligence.ingester.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "attachment-storage")
public record AttachmentStorageProperties(
        String basePath
) {
}
