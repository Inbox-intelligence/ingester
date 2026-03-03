package com.inboxintelligence.ingester.persistence.repository;

import com.inboxintelligence.ingester.model.entity.EmailContent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailContentRepository extends JpaRepository<EmailContent, Long> {

    boolean existsByGmailMailboxIdAndMessageId(Long id, String messageId);
}