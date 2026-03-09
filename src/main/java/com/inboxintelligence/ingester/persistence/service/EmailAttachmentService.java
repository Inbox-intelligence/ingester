package com.inboxintelligence.ingester.persistence.service;

import com.inboxintelligence.ingester.model.entity.EmailAttachment;
import com.inboxintelligence.ingester.persistence.repository.EmailAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailAttachmentService {

    private final EmailAttachmentRepository repository;

    public EmailAttachment save(EmailAttachment attachment) {
        return repository.save(attachment);
    }
}
