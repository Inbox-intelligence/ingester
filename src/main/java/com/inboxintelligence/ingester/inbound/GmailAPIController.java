package com.inboxintelligence.ingester.inbound;

import com.inboxintelligence.ingester.domain.GmailBackfillService;
import com.inboxintelligence.ingester.domain.GmailLabelService;
import com.inboxintelligence.ingester.domain.GmailOAuthLoginService;
import com.inboxintelligence.ingester.domain.GmailTokenService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/gmail-api")
@RequiredArgsConstructor
public class GmailAPIController {

    private final GmailTokenService gmailTokenService;
    private final GmailLabelService gmailLabelService;
    private final GmailBackfillService gmailBackfillService;
    private final GmailOAuthLoginService gmailOAuthLoginService;

    @GetMapping("/login")
    public void invokeOAuthRedirectURI(HttpServletResponse response) {

        log.info("Invoking oAuth Redirect URI");

        try {

            String authUrl = gmailOAuthLoginService.invokeOAuthRedirectURI();
            log.info("Generated Gmail Oauth Redirect URI");
            response.sendRedirect(authUrl);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/token-callback")
    public ResponseEntity<String> processTokenCallbackCode(@RequestParam String code) {

        log.info("Received Authorization Code");
        gmailTokenService.processTokenCallbackCode(code);
        return ResponseEntity.ok("Gmail account connected successfully");
    }

    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> backfill(
            @RequestParam("mailboxAddress") String mailboxAddress,
            @RequestParam(value = "query", required = false, defaultValue = "") String query) {

        CompletableFuture.runAsync(() -> gmailBackfillService.backfill(mailboxAddress, query));
        log.info("Backfill triggered for mailboxAddress={} q='{}'", mailboxAddress, query);
        return ResponseEntity.accepted().body(Map.of("triggered", true, "mailboxAddress", mailboxAddress, "q", query));
    }

    @PostMapping("/fetch-labels")
    public ResponseEntity<Map<String, Object>> fetchLabels(@RequestParam("mailboxAddress") String mailboxAddress) {
        return ResponseEntity.ok(gmailLabelService.fetchLabels(mailboxAddress));
    }
}
