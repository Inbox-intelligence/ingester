package com.inboxintelligence.ingester.model;

public record StoredEmailContentPaths(
        String rawMessageStoragePath,
        String bodyStoragePath,
        String bodyHtmlStoragePath
) {}
