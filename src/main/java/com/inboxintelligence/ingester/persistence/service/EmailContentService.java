package com.inboxintelligence.ingester.persistence.service;

import com.inboxintelligence.ingester.model.entity.EmailContent;
import com.inboxintelligence.ingester.persistence.repository.EmailContentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailContentService {

    private final EmailContentRepository repository;

    public boolean existsByGmailMailboxIdAndMessageId(Long id, String messageId) {
        return repository.existsByGmailMailboxIdAndMessageId(id, messageId);
    }

    public EmailContent save(EmailContent newEmail) {
        return repository.save(newEmail);
    }
}
