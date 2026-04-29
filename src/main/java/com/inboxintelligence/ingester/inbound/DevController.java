package com.inboxintelligence.ingester.inbound;

import com.inboxintelligence.ingester.domain.GmailBackfillService;
import com.inboxintelligence.ingester.outbound.EmailEventPublisher;
import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.service.EmailContentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/dev")
@RequiredArgsConstructor
public class DevController {

    private final EmailContentService emailContentService;
    private final EmailEventPublisher emailEventPublisher;
    private final GmailBackfillService gmailBackfillService;

    @PostMapping("/republish-all")
    public ResponseEntity<String> republishAll() {

        List<EmailContent> emails = emailContentService.findAll();
        log.info("Republishing {} emails to RabbitMQ", emails.size());

        for (EmailContent email : emails) {
            emailEventPublisher.publishEmailProcessed(email);
        }

        return ResponseEntity.ok("Republished " + emails.size() + " emails to RabbitMQ");
    }

    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> backfill(
            @RequestParam("mailboxAddress") String mailboxAddress,
            @RequestParam(value = "query", required = false, defaultValue = "") String query) {

        CompletableFuture.runAsync(() -> gmailBackfillService.backfill(mailboxAddress, query));
        log.info("Backfill triggered for mailboxAddress={} q='{}'", mailboxAddress, query);
        return ResponseEntity.accepted().body(Map.of("triggered", true, "mailboxAddress", mailboxAddress, "q", query));
    }
}
