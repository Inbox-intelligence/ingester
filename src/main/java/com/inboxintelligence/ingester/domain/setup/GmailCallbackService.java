package com.inboxintelligence.ingester.domain.setup;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.WatchResponse;
import com.inboxintelligence.ingester.config.GmailApiProperties;
import com.inboxintelligence.ingester.outbound.GmailApiClient;
import com.inboxintelligence.ingester.outbound.GmailClientFactory;
import com.inboxintelligence.persistence.model.enums.SyncStatus;
import com.inboxintelligence.persistence.model.entity.GmailMailbox;
import com.inboxintelligence.persistence.service.GmailMailboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailCallbackService {

    private final GmailApiProperties gmailApiProperties;
    private final GmailMailboxService gmailMailboxService;
    private final GmailClientFactory gmailClientFactory;
    private final GmailApiClient gmailApiClient;

    public void processTokenCallbackCode(String authorizationCode) {

        log.debug("Processing Gmail OAuth callback");

        try {

            GoogleTokenResponse tokenResponse = gmailClientFactory.createAuthorizationCodeTokenRequest(authorizationCode).execute();
            String emailAddress = verifyAndExtractEmail(tokenResponse);
            WatchResponse watchResponse = startMailboxWatch(tokenResponse);

            saveGmailMailbox(tokenResponse, watchResponse, emailAddress);
            log.info("Gmail mailbox onboarding completed for {}", emailAddress);

        } catch (Exception e) {
            log.error("Gmail OAuth onboarding failed", e);
            throw new IllegalStateException("OAuth token exchange failed", e);
        }
    }

    private String verifyAndExtractEmail(GoogleTokenResponse tokenResponse) throws Exception {

        log.debug("Verifying Google ID token");
        GoogleIdToken idToken = gmailClientFactory.createIdTokenVerifier().verify(tokenResponse.getIdToken());

        if (idToken == null) {
            throw new IllegalStateException("Invalid Google ID token");
        }

        String email = idToken.getPayload().getEmail();
        log.debug("Authenticated Gmail user {}", email);

        return email;
    }

    private WatchResponse startMailboxWatch(GoogleTokenResponse tokenResponse) {

        log.debug("Starting Gmail mailbox watch (Pub/Sub)");

        Gmail gmail = gmailClientFactory.createUsingGoogleTokenResponse(tokenResponse);
        WatchResponse response = gmailApiClient.watchMailbox(gmail, gmailApiProperties.pubsubTopic(), List.of("INBOX"));

        log.info("Mailbox watch started. historyId={}, expiresAt={}", response.getHistoryId(), response.getExpiration());

        return response;
    }

    private void saveGmailMailbox(GoogleTokenResponse tokenResponse, WatchResponse watchResponse, String email) {

        GmailMailbox gmailMailbox = gmailMailboxService.findByEmailAddress(email).orElseGet(GmailMailbox::new);
        Instant accessTokenExpiresAt = Instant.now().plusSeconds(tokenResponse.getExpiresInSeconds());

        String refreshToken = tokenResponse.getRefreshToken();
        if (refreshToken == null) {
            if (gmailMailbox.getRefreshToken() == null) {
                throw new IllegalStateException("Google did not return a refresh_token and no existing one is stored for " + email + ". Revoke access in the Google account and retry.");
            }
            log.info("No new refresh_token returned for {} — keeping existing one", email);
            refreshToken = gmailMailbox.getRefreshToken();
        }

        gmailMailbox.setEmailAddress(email);
        gmailMailbox.setAccessToken(tokenResponse.getAccessToken());
        gmailMailbox.setRefreshToken(refreshToken);
        gmailMailbox.setAccessTokenExpiresAt(accessTokenExpiresAt);
        gmailMailbox.setHistoryId(watchResponse.getHistoryId().longValue());
        gmailMailbox.setWatchExpiresAt(watchResponse.getExpiration());
        gmailMailbox.setSyncStatus(SyncStatus.ACTIVE);
        gmailMailbox.setLastSyncError(null);

        gmailMailboxService.save(gmailMailbox);
        log.debug("Persisted Gmail mailbox entity for {}", email);
    }
}
