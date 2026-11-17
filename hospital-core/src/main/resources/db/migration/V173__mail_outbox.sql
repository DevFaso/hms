-- =====================================================================
-- V173: the outbound mail outbox (platform.mail_outbox)
--
-- Mail was handed to SMTP on the request thread, from an after-commit
-- callback that runs BEFORE the request's JDBC connection is released: a
-- stalled mail host pinned a Hikari connection and a request thread for the
-- whole send timeout (JavaMail's default is infinite) on registration,
-- account restore, the assignment notifications and every other mail. The
-- request now composes the message and parks it in this table; a ShedLock-guarded
-- sweep claims each row with a conditional UPDATE and sends it with no
-- transaction open, the webhook-outbox mechanics of V152.
--
-- Columns:
--   * recipients / cc_recipients / bcc_recipients: newline-separated
--     addresses. TEXT because the app encrypts them (EncryptedStringConverter,
--     AES-GCM), and ciphertext outgrows any VARCHAR an address would need.
--   * subject / html_body: encrypted too. Bodies carry activation links,
--     one-time codes, temporary passwords and names. Both are set to NULL as
--     soon as the row is terminal (SENT or FAILED), hence nullable.
--   * status: PENDING -> SENT | FAILED.
--   * attempts / last_attempt_at / last_error: the retry vocabulary of
--     V119/V152. last_error holds an exception class name only, never its
--     message (a transport message can quote the address).
--   * next_attempt_at: when the sweep may pick the row up again: the doubling
--     backoff after a failure, and the claim lease while an instance sends it.
--   * created_at / updated_at: NOT NULL because the entity extends
--     BaseEntity (the V153 lesson).
--
-- Terminal rows are deleted by the sweep after the retention window
-- (app.mail.outbox.retention-days, default 30).
--
-- Strictly additive: CREATE TABLE / CREATE INDEX IF NOT EXISTS only. No
-- automated rollback is declared; to undo, DROP TABLE platform.mail_outbox
-- (queued mail is lost).
-- =====================================================================

CREATE TABLE IF NOT EXISTS platform.mail_outbox (
    id               UUID          NOT NULL,
    recipients       TEXT          NOT NULL,
    cc_recipients    TEXT,
    bcc_recipients   TEXT,
    subject          TEXT,
    html_body        TEXT,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts         INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMP     NOT NULL,
    last_attempt_at  TIMESTAMP,
    sent_at          TIMESTAMP,
    last_error       VARCHAR(200),
    created_at       TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP     NOT NULL,

    CONSTRAINT pk_mail_outbox PRIMARY KEY (id)
);

-- The sweep: due PENDING rows, most overdue first.
CREATE INDEX IF NOT EXISTS idx_mail_outbox_dispatch
    ON platform.mail_outbox (status, next_attempt_at);
