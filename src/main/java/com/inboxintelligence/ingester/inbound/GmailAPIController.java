package com.inboxintelligence.ingester.inbound;

import com.inboxintelligence.ingester.domain.label.GmailLabelPublishService;
import com.inboxintelligence.ingester.domain.label.GmailLabelFetchService;
import com.inboxintelligence.ingester.domain.label.GmailLabelCreateService;
import com.inboxintelligence.ingester.domain.message.GmailMessageBackfillService;
import com.inboxintelligence.ingester.domain.setup.GmailCallbackService;
import com.inboxintelligence.ingester.domain.setup.GmailLoginHelper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/gmail-api")
@RequiredArgsConstructor
public class GmailAPIController {

    private final GmailCallbackService gmailCallbackService;
    private final GmailLabelFetchService gmailLabelFetchService;
    private final GmailLabelCreateService gmailLabelCreateService;
    private final GmailLabelPublishService gmailLabelPublishService;
    private final GmailMessageBackfillService gmailMessageBackfillService;
    private final GmailLoginHelper gmailLoginHelper;

    @GetMapping("/login")
    public void invokeOAuthRedirectURI(HttpServletResponse response) {

        log.info("Invoking oAuth Redirect URI");

        try {

            String authUrl = gmailLoginHelper.prepareOAuthRedirectURI();
            log.info("Generated Gmail Oauth Redirect URI");
            response.sendRedirect(authUrl);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/token-callback")
    public ResponseEntity<String> processTokenCallbackCode(@RequestParam String code) {

        log.info("Received Authorization Code");
        gmailCallbackService.processTokenCallbackCode(code);
        return ResponseEntity.ok("Gmail account connected successfully");
    }

    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> backfill(
            @RequestParam("mailboxAddress") String mailboxAddress,
            @RequestParam(value = "query", required = false, defaultValue = "") String query) {

        CompletableFuture.runAsync(() -> gmailMessageBackfillService.backfill(mailboxAddress, query))
                .exceptionally(ex -> {
                    log.error("Backfill failed for mailboxAddress={} q='{}'", mailboxAddress, query, ex);
                    return null;
                });
        log.info("Backfill triggered for mailboxAddress={} q='{}'", mailboxAddress, query);
        return ResponseEntity.accepted().body(Map.of("triggered", true, "mailboxAddress", mailboxAddress, "q", query));
    }

    @PostMapping("/fetch-labels")
    public ResponseEntity<Map<String, Object>> fetchLabels(@RequestParam("mailboxAddress") String mailboxAddress) {
        return ResponseEntity.ok(gmailLabelFetchService.fetchLabels(mailboxAddress));
    }

    @PostMapping("/labels")
    public ResponseEntity<Boolean> createLabel(
            @RequestParam("mailboxAddress") String mailboxAddress,
            @RequestParam("labelName") String labelName) {
        gmailLabelCreateService.createLabel(mailboxAddress, labelName);
        return ResponseEntity.ok(true);
    }

    @PostMapping("/publish-labels")
    public ResponseEntity<Map<String, Object>> publishLabels(@RequestParam("mailboxAddress") String mailboxAddress) {
        return ResponseEntity.ok(gmailLabelPublishService.publishLabels(mailboxAddress));
    }

    @PostMapping("/messages/labels")
    public ResponseEntity<Map<String, Object>> applyLabelsToMessages(
            @RequestParam("mailboxAddress") String mailboxAddress,
            @RequestBody Map<String, List<String>> request) {
        List<String> messageIds = request.getOrDefault("messageIds", Collections.emptyList());
        List<String> addLabelIds = request.getOrDefault("addLabelIds", Collections.emptyList());
        List<String> removeLabelIds = request.getOrDefault("removeLabelIds", Collections.emptyList());
        return ResponseEntity.ok(gmailLabelPublishService.applyLabelsToMessages(mailboxAddress, messageIds, addLabelIds, removeLabelIds));
    }
}
