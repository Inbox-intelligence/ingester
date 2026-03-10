-- Move raw_message, body, and body_html out of DB into file storage.
-- The application now persists these as files and stores only the path.

ALTER TABLE email_content
    ADD COLUMN IF NOT EXISTS raw_message_storage_path VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS body_storage_path        VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS body_html_storage_path   VARCHAR(1024);

ALTER TABLE email_content
    DROP COLUMN IF EXISTS raw_message,
    DROP COLUMN IF EXISTS body,
    DROP COLUMN IF EXISTS body_html;
