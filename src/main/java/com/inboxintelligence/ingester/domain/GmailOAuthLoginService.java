package com.inboxintelligence.ingester.domain;

import com.inboxintelligence.ingester.config.GmailApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Service
@RequiredArgsConstructor
public class GmailOAuthLoginService {

    private final GmailApiProperties gmailApiProperties;

    public String invokeOAuthRedirectURI() {

        log.debug("Building OAuth redirect URI for clientId={}", gmailApiProperties.clientId());

        return UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", gmailApiProperties.clientId())
                .queryParam("redirect_uri", gmailApiProperties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", gmailApiProperties.scope())
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .build()
                .encode()
                .toUriString();
    }
}