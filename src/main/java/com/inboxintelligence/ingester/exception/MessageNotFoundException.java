package com.inboxintelligence.ingester.exception;

/**
 * Thrown when a Gmail message no longer exists (404).
 * This is expected when a message is deleted before we fetch it — not retryable.
 */
public class MessageNotFoundException extends RuntimeException {

    public MessageNotFoundException(String message) {
        super(message);
    }
}
