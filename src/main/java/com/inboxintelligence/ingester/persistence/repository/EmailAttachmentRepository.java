package com.inboxintelligence.ingester.persistence.repository;

import com.inboxintelligence.ingester.model.entity.EmailAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailAttachmentRepository extends JpaRepository<EmailAttachment, Long> {

}