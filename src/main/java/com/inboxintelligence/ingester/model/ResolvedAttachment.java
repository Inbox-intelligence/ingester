package com.inboxintelligence.ingester.model;

public record ResolvedAttachment(
        String fileName,
        String mimeType,
        String attachmentId,
        long sizeInBytes,
        byte[] data,
        boolean isInline
) {}
