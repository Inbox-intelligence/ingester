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

@Slf4j
@Component
public class GmailApiClient {

    @Retry(name = "gmailRetry")
    public ListHistoryResponse fetchHistory(Gmail gmail, long startHistoryId, String pageToken) {

        log.debug("Gmail API: fetchHistory startHistoryId={} pageToken={}", startHistoryId, pageToken);
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
            throw new RetryableGmailApiException("Retrying fetchHistory: " + e.getMessage(), e);
        }
    }

    @Retry(name = "gmailRetry")
    public Message fetchMessage(Gmail gmail, String messageId) {

        log.debug("Gmail API: fetchMessage messageId={}", messageId);
        try {
            return gmail.users()
                    .messages()
                    .get("me", messageId)
                    .setFormat("full")
                    .execute();
        } catch (GoogleJsonResponseException ex) {
            if (ex.getStatusCode() == 404) {
                log.warn("Gmail API: fetchMessage messageId={} not found (deleted/moved) — skipping", messageId);
                return null;
            }
            throw handleGoogleJsonException(ex, "fetchMessage");
        } catch (IOException e) {
            throw new RetryableGmailApiException("Retrying fetchMessage: " + e.getMessage(), e);
        }
    }

    @Retry(name = "gmailRetry")
    public MessagePartBody fetchAttachment(Gmail gmail, String messageId, String attachmentId) {

        log.debug("Gmail API: fetchAttachment messageId={} attachmentId={}", messageId, attachmentId);
        try {
            return gmail.users()
                    .messages()
                    .attachments()
                    .get("me", messageId, attachmentId)
                    .execute();
        } catch (GoogleJsonResponseException ex) {
            throw handleGoogleJsonException(ex, "fetchAttachment");
        } catch (IOException e) {
            throw new RetryableGmailApiException("Retrying fetchAttachment: " + e.getMessage(), e);
        }
    }

    @Retry(name = "gmailRetry")
    public ListMessagesResponse listMessages(Gmail gmail, String query, String pageToken) {

        log.debug("Gmail API: listMessages query='{}' pageToken={}", query, pageToken);
        try {
            return gmail.users()
                    .messages()
                    .list("me")
                    .setQ(query)
                    .setPageToken(pageToken)
                    .setMaxResults(500L)
                    .execute();
        } catch (GoogleJsonResponseException ex) {
            throw handleGoogleJsonException(ex, "listMessages");
        } catch (IOException e) {
            throw new RetryableGmailApiException("Retrying listMessages: " + e.getMessage(), e);
        }
    }

    @Retry(name = "gmailRetry")
    public ListLabelsResponse listLabels(Gmail gmail) {

        log.debug("Gmail API: listLabels");
        try {
            return gmail.users().labels().list("me").execute();
        } catch (GoogleJsonResponseException ex) {
            throw handleGoogleJsonException(ex, "listLabels");
        } catch (IOException e) {
            throw new RetryableGmailApiException("Retrying listLabels: " + e.getMessage(), e);
        }
    }

    @Retry(name = "gmailRetry")
    public WatchResponse watchMailbox(Gmail gmail, String topic, List<String> labelIds) {

        log.debug("Gmail API: watchMailbox topic={} labelIds={}", topic, labelIds);
        try {
            WatchRequest watchRequest = new WatchRequest()
                    .setTopicName(topic)
                    .setLabelIds(labelIds);
            return gmail.users().watch("me", watchRequest).execute();
        } catch (GoogleJsonResponseException ex) {
            throw handleGoogleJsonException(ex, "watchMailbox");
        } catch (IOException e) {
            throw new RetryableGmailApiException("Retrying watchMailbox: " + e.getMessage(), e);
        }
    }

    @Retry(name = "gmailRetry")
    public Label createLabel(Gmail gmail, String labelName) {

        log.debug("Gmail API: createLabel name='{}'", labelName);
        try {
            Label label = new Label()
                    .setName(labelName)
                    .setLabelListVisibility("labelShow")
                    .setMessageListVisibility("show");
            return gmail.users().labels().create("me", label).execute();
        } catch (GoogleJsonResponseException ex) {

            if (ex.getStatusCode() == 409) {
                log.warn("Gmail label '{}' already exists — fetching existing", labelName);
                Label existing = findLabelByName(gmail, labelName);
                if (existing == null) {
                    log.warn("Gmail returned 409 for label '{}' but list lookup did not find it", labelName);
                }
                return existing;
            }
            throw handleGoogleJsonException(ex, "createLabel");
        } catch (IOException e) {
            throw new RetryableGmailApiException("Retrying createLabel: " + e.getMessage(), e);
        }
    }

    private Label findLabelByName(Gmail gmail, String labelName) {
        ListLabelsResponse response = listLabels(gmail);
        if (response == null || response.getLabels() == null) {
            return null;
        }
        return response.getLabels().stream()
                .filter(l -> labelName.equals(l.getName()))
                .findFirst()
                .orElse(null);
    }

    @Retry(name = "gmailRetry")
    public void batchModifyMessages(Gmail gmail, List<String> messageIds, List<String> addLabelIds, List<String> removeLabelIds) {

        log.debug("Gmail API: batchModifyMessages messageCount={} add={} remove={}", messageIds.size(), addLabelIds, removeLabelIds);
        try {
            BatchModifyMessagesRequest request = new BatchModifyMessagesRequest()
                    .setIds(messageIds)
                    .setAddLabelIds(addLabelIds)
                    .setRemoveLabelIds(removeLabelIds);
            gmail.users().messages().batchModify("me", request).execute();
        } catch (GoogleJsonResponseException ex) {
            throw handleGoogleJsonException(ex, "batchModifyMessages");
        } catch (IOException e) {
            throw new RetryableGmailApiException("Retrying batchModifyMessages: " + e.getMessage(), e);
        }
    }

    private RuntimeException handleGoogleJsonException(GoogleJsonResponseException ex, String operation) {

        int status = ex.getStatusCode();

        if (status == 429 || status >= 500) {
            log.warn("Gmail API: {} returned retryable status {}", operation, status);
            return new RetryableGmailApiException("Retrying " + operation + ": Received " + status, ex);
        }

        if (status == 404) {
            log.warn("Gmail API: {} — entity not found (status 404)", operation);
            return new RuntimeException(operation + ": not found", ex);
        }

        log.error("Gmail API: {} returned non-retryable status {}", operation, status, ex);
        return new RuntimeException("Non-retryable Gmail error in " + operation, ex);
    }
}
