package com.inboxintelligence.ingester.outbound;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.*;
import com.inboxintelligence.ingester.exception.RetryableGmailApiException;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

/** Low-level Gmail API client with resilience4j retry for transient errors. */
@Component
@Slf4j
public class GmailApiClient {

    @Retry(name = "gmailRetry")
    public ListHistoryResponse fetchHistory(Gmail gmail, long startHistoryId, String pageToken) {

        try {
            return gmail.users()
                    .history()
                    .list("me")
                    .setStartHistoryId(BigInteger.valueOf(startHistoryId))
                    .setPageToken(pageToken)
                    .execute();
        } catch (GoogleJsonResponseException ex) {
            throw handleGoogleJsonException(ex, "fetchHistory");
        } catch (IOException e) {
            throw new RetryableGmailApiException("Retrying fetchHistory: " + e.getMessage());
        }
    }

    @Retry(name = "gmailRetry")
    public Message fetchMessage(Gmail gmail, String messageId) {

        try {
            return gmail.users()
                    .messages()
                    .get("me", messageId)
                    .setFormat("full")
                    .execute();
        } catch (GoogleJsonResponseException ex) {
            throw handleGoogleJsonException(ex, "fetchMessage");
        } catch (IOException e) {
            throw new RetryableGmailApiException("Retrying fetchMessage: " + e.getMessage());
        }
    }

    @Retry(name = "gmailRetry")
    public MessagePartBody fetchAttachment(Gmail gmail, String messageId, String attachmentId) {

        try {
            return gmail.users()
                    .messages()
                    .attachments()
                    .get("me", messageId, attachmentId)
                    .execute();
        } catch (GoogleJsonResponseException ex) {
            throw handleGoogleJsonException(ex, "fetchAttachment");
        } catch (IOException e) {
            throw new RetryableGmailApiException("Retrying fetchAttachment: " + e.getMessage());
        }
    }

    @Retry(name = "gmailRetry")
    public WatchResponse watchMailbox(Gmail gmail, String topic, List<String> labelIds) {

        try {
            var watchRequest = new WatchRequest()
                    .setTopicName(topic)
                    .setLabelIds(labelIds);
            return gmail.users().watch("me", watchRequest).execute();
        } catch (GoogleJsonResponseException ex) {
            throw handleGoogleJsonException(ex, "watchMailbox");
        } catch (IOException e) {
            throw new RetryableGmailApiException("Retrying watchMailbox: " + e.getMessage());
        }
    }

    private RuntimeException handleGoogleJsonException(GoogleJsonResponseException ex, String operation) {

        int status = ex.getStatusCode();

        if (status == 429 || status >= 500) {
            return new RetryableGmailApiException("Retrying " + operation + ": Received " + status);
        }

        return new IllegalArgumentException("Non-retryable Gmail error in " + operation, ex);
    }
}
