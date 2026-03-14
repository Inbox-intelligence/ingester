package com.inboxintelligence.ingester.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "email-storage")
public record EmailStorageProperties(
        String provider,
        String localBasePath
) {
}