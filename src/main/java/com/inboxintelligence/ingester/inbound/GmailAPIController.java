package com.inboxintelligence.ingester.inbound;

import com.inboxintelligence.ingester.domain.GmailOAuthLoginService;
import com.inboxintelligence.ingester.domain.GmailTokenService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/gmail-api")
@RequiredArgsConstructor
public class GmailAPIController {

    private final GmailOAuthLoginService gmailOAuthLoginService;
    private final GmailTokenService gmailTokenService;

    @GetMapping("/login")
    public void invokeOAuthRedirectURI(HttpServletResponse response) {

        log.info("Invoking oAuth Redirect URI");

        try {

            var authUrl = gmailOAuthLoginService.invokeOAuthRedirectURI();
            log.info("Generated Gmail Oauth Redirect URI");
            response.sendRedirect(authUrl);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/token-callback")
    public void processTokenCallbackCode(@RequestParam String code) {

        log.info("Received Authorization Code");
        gmailTokenService.processTokenCallbackCode(code);

    }
}
