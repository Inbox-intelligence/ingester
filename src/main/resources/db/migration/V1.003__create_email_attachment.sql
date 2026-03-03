CREATE TABLE email_attachment (

    id BIGSERIAL PRIMARY KEY,

    fk_email_content_id BIGINT NOT NULL,

    -- gmail metadata
    email_attachment_id VARCHAR(1024),
    file_name VARCHAR(1024) NOT NULL,
    mime_type VARCHAR(255),
    size_in_bytes BIGINT,

    -- storage
    storage_path VARCHAR(1024) NOT NULL,
    storage_provider VARCHAR(8) NOT NULL DEFAULT 'S3',

    -- processing
    is_inline BOOLEAN NOT NULL DEFAULT FALSE,
    is_processed BOOLEAN NOT NULL DEFAULT FALSE,

    -- audit
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_gmail_email_attachment_inbox
            FOREIGN KEY (fk_email_content_id)
            REFERENCES email_content(id)
            ON DELETE CASCADE
);

CREATE INDEX idx_inbox_attachment ON email_attachment(fk_email_content_id);