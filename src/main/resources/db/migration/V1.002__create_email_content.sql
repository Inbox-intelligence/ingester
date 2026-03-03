CREATE TABLE email_content (

    id BIGSERIAL PRIMARY KEY,

    fk_gmail_mailbox_id BIGINT NOT NULL,

    -- vendor identifiers
    message_id VARCHAR(1024) NOT NULL,
    thread_id VARCHAR(1024) NOT NULL,
    parent_message_id VARCHAR(1024) DEFAULT NULL,
    raw_message TEXT,

    -- content
    subject TEXT,
    from_address TEXT,
    to_address TEXT,
    cc_address TEXT,
    body TEXT,

    sent_at TIMESTAMP,
    received_at TIMESTAMP,

    is_processed BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_email_content_mailbox
        FOREIGN KEY (fk_gmail_mailbox_id)
        REFERENCES gmail_mailbox(id)
        ON DELETE CASCADE,

    CONSTRAINT uq_inbox_message
        UNIQUE (fk_gmail_mailbox_id, message_id)
);


CREATE INDEX idx_inbox_mailbox ON email_content (fk_gmail_mailbox_id);
CREATE INDEX idx_inbox_thread ON email_content (thread_id);
CREATE INDEX idx_inbox_parent ON email_content (parent_message_id);

