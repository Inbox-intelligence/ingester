package com.inboxintelligence.ingester.exception;

public class RetryableGmailApiException extends RuntimeException {

    public RetryableGmailApiException(String message) {
        super(message);
    }

    public RetryableGmailApiException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
