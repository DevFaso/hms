-- V68: P1 #10 — Telehealth low-bandwidth (audio + photo on chat)
--
-- Adds first-class attachment support to support.chat_messages so a
-- telehealth consult can carry a short voice memo (Opus/AAC) or a
-- diagnostic photo without introducing video / WebRTC infrastructure.
--
-- Each chat message can carry zero-or-more attachments (max 4 enforced
-- in service). Storage path follows the existing FileUploadService
-- chart-attachments convention: server-renamed file under
-- /uploads/chat-attachments/, SHA-256 captured for tamper detection.
--
-- duration_seconds is populated for AUDIO only (client-measured at
-- record time, server-clamped to <= 90s).
--
-- Additive only; pure DDL.

CREATE TABLE IF NOT EXISTS support.chat_attachments (
    id                  UUID PRIMARY KEY,
    message_id          UUID NOT NULL,
    kind                VARCHAR(16) NOT NULL,
    storage_key         VARCHAR(512) NOT NULL,
    public_url          VARCHAR(1024) NOT NULL,
    display_name        VARCHAR(255) NOT NULL,
    content_type        VARCHAR(128) NOT NULL,
    size_bytes          BIGINT NOT NULL,
    sha256              VARCHAR(64) NOT NULL,
    duration_seconds    INTEGER,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_chat_attachment_message FOREIGN KEY (message_id)
        REFERENCES support.chat_messages(id) ON DELETE CASCADE,
    CONSTRAINT chk_chat_attachment_kind CHECK (kind IN ('PHOTO', 'AUDIO')),
    CONSTRAINT chk_chat_attachment_size CHECK (size_bytes > 0),
    CONSTRAINT chk_chat_attachment_audio_duration
        CHECK ((kind = 'AUDIO' AND duration_seconds IS NOT NULL AND duration_seconds BETWEEN 1 AND 90)
            OR (kind = 'PHOTO' AND duration_seconds IS NULL))
);

-- Hot path: render attachments under a chat message in chronological order.
CREATE INDEX IF NOT EXISTS idx_chat_attachments_message
    ON support.chat_attachments (message_id, created_at);

-- Storage-key uniqueness so the same uploaded file can't be re-linked
-- to two messages (which would also break the ON DELETE CASCADE story).
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_attachments_storage_key
    ON support.chat_attachments (storage_key);
