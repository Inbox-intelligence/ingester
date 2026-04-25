package com.inboxintelligence.ingester.outbound;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
import com.inboxintelligence.ingester.config.GmailApiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;

@Component
@Slf4j
public class GmailClientFactory {

    private final GmailApiProperties gmailApiProperties;
    private final NetHttpTransport httpTransport;
    private final GsonFactory gsonFactory;

    public GmailClientFactory(GmailApiProperties gmailApiProperties) throws GeneralSecurityException, IOException {
        this.gmailApiProperties = gmailApiProperties;
        this.httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        this.gsonFactory = GsonFactory.getDefaultInstance();
    }

    public Gmail createUsingGoogleTokenResponse(GoogleTokenResponse googleTokenResponse) {
        log.debug("Creating Gmail client using token response");
        Instant expiresAt = Instant.now().plusSeconds(googleTokenResponse.getExpiresInSeconds());
        AccessToken accesstoken = new AccessToken(googleTokenResponse.getAccessToken(), Date.from(expiresAt));
        GoogleCredentials credentials = GoogleCredentials.create(accesstoken);
        return this.create(credentials);
    }

    public Gmail createUsingRefreshToken(String refreshToken) {
        log.debug("Creating Gmail client using refresh token");
        UserCredentials credentials = UserCredentials.newBuilder()
                .setClientId(gmailApiProperties.clientId())
                .setClientSecret(gmailApiProperties.clientSecret())
                .setRefreshToken(refreshToken)
                .build();
        return this.create(credentials);
    }

    private Gmail create(GoogleCredentials credentials) {

        HttpCredentialsAdapter httpRequestInitializer = new HttpCredentialsAdapter(credentials) {
            @Override
            public void initialize(HttpRequest httpRequest) throws IOException {
                super.initialize(httpRequest);
                httpRequest.setConnectTimeout(5000);  // 5 seconds
                httpRequest.setReadTimeout(10000);    // 10 seconds
            }
        };

        return new Gmail.Builder(httpTransport, gsonFactory, httpRequestInitializer)
                .setApplicationName(gmailApiProperties.applicationName())
                .build();
    }

    public GoogleAuthorizationCodeTokenRequest createAuthorizationCodeTokenRequest(String authorizationCode) {
        log.debug("Creating authorization code token request");
        return new GoogleAuthorizationCodeTokenRequest(
                httpTransport,
                gsonFactory,
                gmailApiProperties.tokenUrl(),
                gmailApiProperties.clientId(),
                gmailApiProperties.clientSecret(),
                authorizationCode,
                gmailApiProperties.redirectUri()
        );
    }

    public GoogleIdTokenVerifier createIdTokenVerifier() {
        return new GoogleIdTokenVerifier.Builder(httpTransport, gsonFactory)
                .setAudience(Collections.singletonList(gmailApiProperties.clientId()))
                .build();
    }
}
