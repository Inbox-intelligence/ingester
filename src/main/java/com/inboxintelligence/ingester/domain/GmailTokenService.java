package com.inboxintelligence.ingester.domain;

import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.services.gmail.model.WatchResponse;
import com.inboxintelligence.ingester.config.GmailApiProperties;
import com.inboxintelligence.ingester.outbound.GmailApiClient;
import com.inboxintelligence.ingester.outbound.GmailClientFactory;
import com.inboxintelligence.persistence.model.SyncStatus;
import com.inboxintelligence.persistence.model.entity.GmailMailbox;
import com.inboxintelligence.persistence.service.GmailMailboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Handles OAuth token exchange, ID verification, and mailbox onboarding.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GmailTokenService {

    private final GmailApiProperties gmailApiProperties;
    private final GmailMailboxService gmailMailboxService;
    private final GmailClientFactory gmailClientFactory;
    private final GmailApiClient gmailApiClient;

    public void processTokenCallbackCode(String authorizationCode) {

        log.info("Processing Gmail OAuth callback");

        try {

            var tokenResponse = gmailClientFactory.createAuthorizationCodeTokenRequest(authorizationCode).execute();
            var emailAddress = verifyAndExtractEmail(tokenResponse);
            var watchResponse = startMailboxWatch(tokenResponse);

            saveGmailMailbox(tokenResponse, watchResponse, emailAddress);
            log.info("Gmail mailbox onboarding completed for {}", emailAddress);

        } catch (Exception e) {
            log.error("Gmail OAuth onboarding failed", e);
            throw new IllegalStateException("OAuth token exchange failed", e);
        }
    }

    private String verifyAndExtractEmail(GoogleTokenResponse tokenResponse) throws Exception {

        log.info("Verifying Google ID token");
        var idToken = gmailClientFactory.createIdTokenVerifier().verify(tokenResponse.getIdToken());

        if (idToken == null) {
            throw new IllegalStateException("Invalid Google ID token");
        }

        String email = idToken.getPayload().getEmail();
        log.info("Authenticated Gmail user {}", email);

        return email;
    }

    private WatchResponse startMailboxWatch(GoogleTokenResponse tokenResponse) {

        log.info("Starting Gmail mailbox watch (Pub/Sub)");

        var gmail = gmailClientFactory.createUsingGoogleTokenResponse(tokenResponse);
        var response = gmailApiClient.watchMailbox(
                gmail, gmailApiProperties.pubsubTopic(), List.of("INBOX"));

        log.info("Mailbox watch started. historyId={}, expiresAt={}", response.getHistoryId(), response.getExpiration());

        return response;
    }

    private void saveGmailMailbox(GoogleTokenResponse tokenResponse, WatchResponse watchResponse, String email) {

        var gmailMailbox = gmailMailboxService.findByEmailAddress(email).orElseGet(GmailMailbox::new);
        var accessTokenExpiresAt = Instant.now().plusSeconds(tokenResponse.getExpiresInSeconds());

        gmailMailbox.setEmailAddress(email);
        gmailMailbox.setAccessToken(tokenResponse.getAccessToken());
        gmailMailbox.setRefreshToken(tokenResponse.getRefreshToken());
        gmailMailbox.setAccessTokenExpiresAt(accessTokenExpiresAt);
        gmailMailbox.setHistoryId(watchResponse.getHistoryId().longValue());
        gmailMailbox.setWatchExpiresAt(watchResponse.getExpiration());
        gmailMailbox.setSyncStatus(SyncStatus.ACTIVE);
        gmailMailbox.setLastSyncError(null);

        gmailMailboxService.save(gmailMailbox);
        log.info("Persisted Gmail mailbox entity for {}", email);
    }
}
