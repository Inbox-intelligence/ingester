package com.inboxintelligence.ingester.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "email-content-storage")
public record EmailContentStorageProperties(
        String attachmentPath
) {
}
