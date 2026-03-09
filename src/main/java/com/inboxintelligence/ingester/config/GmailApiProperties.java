package com.inboxintelligence.ingester.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gmail-api")
public record GmailApiProperties(
        String applicationName,
        String clientId,
        String clientSecret,
        String redirectUri,
        String tokenUrl,
        String scope,
        String pubsubTopic,
        String projectId,
        String pubsubSubscriptionId
) {
}