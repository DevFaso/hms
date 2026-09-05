-- V152: outbound webhooks + API-key management (Tier 2 item 45).
--
-- WHY: third-party clients had no way in and no way to be told. The only
-- "API credential" in the product was organization_platform_services.
-- api_key_reference -- a pointer HMS uses when calling OUT, with no
-- issuance, rotation or verification behind it (and it leaked: returned in
-- the response DTO, rendered in the portal, copied into the Kafka event).
-- This migration adds the real thing, both directions:
--
--   * platform.api_keys       -- keys HMS ISSUES to third-party clients.
--     Only the SHA-256 hash is stored (the PasswordResetToken precedent);
--     the raw key is shown exactly once at issuance/rotation. Keys are
--     revoked, never deleted -- an issued credential is an auditable fact.
--     expires_on is NULLABLE on purpose (the V145 lesson: never make an
--     expiry mandatory).
--   * platform.webhook_endpoints -- URLs HMS CALLS when subscribed events
--     occur. secret is TEXT because the app encrypts it at rest
--     (EncryptedStringConverter) -- it signs every delivery (HMAC-SHA256).
--   * platform.webhook_endpoint_events -- the subscription list.
--   * platform.webhook_deliveries -- the outbox, copied from the
--     instrument-outbox mechanics (V28/V119): attempts / last_error /
--     last_attempt_at, swept by a scheduler with a retry ceiling.
--     Payloads are thin id-references ONLY -- no PHI leaves the system in
--     a webhook body.
--
-- Also widens the three pre-existing credential-pointer columns to TEXT so
-- the app can encrypt them at rest (ciphertext outgrows VARCHAR(120);
-- existing plaintext values read back verbatim -- the converter's
-- rolling-migration behaviour).
--
-- version columns: optimistic lock (the V149 lesson) -- a concurrent
-- rotate/revoke must not silently double-issue.
--
-- Rollback:
--   DROP TABLE platform.webhook_deliveries;
--   DROP TABLE platform.webhook_endpoint_events;
--   DROP TABLE platform.webhook_endpoints;
--   DROP TABLE platform.api_keys;
--   (the ALTER COLUMN TYPE widenings are harmless to leave)
-- =============================================================================

CREATE TABLE IF NOT EXISTS platform.api_keys (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    hospital_id   UUID         NOT NULL,
    label         VARCHAR(120) NOT NULL,
    key_prefix    VARCHAR(16)  NOT NULL,
    key_hash      VARCHAR(64)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    expires_on    DATE,
    last_used_at  TIMESTAMP,
    revoked_at    TIMESTAMP,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_by    VARCHAR(255),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_api_keys          PRIMARY KEY (id),
    CONSTRAINT fk_api_key_hospital  FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals(id),
    CONSTRAINT uq_api_key_hash      UNIQUE (key_hash)
);

CREATE INDEX IF NOT EXISTS idx_api_keys_hospital
    ON platform.api_keys (hospital_id, status);

CREATE TABLE IF NOT EXISTS platform.webhook_endpoints (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid(),
    hospital_id           UUID         NOT NULL,
    url                   VARCHAR(500) NOT NULL,
    description           VARCHAR(255),
    secret                TEXT         NOT NULL,
    status                VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    consecutive_failures  INT          NOT NULL DEFAULT 0,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_by            VARCHAR(255),
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_webhook_endpoints         PRIMARY KEY (id),
    CONSTRAINT fk_webhook_endpoint_hospital FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals(id)
);

CREATE INDEX IF NOT EXISTS idx_webhook_endpoints_hospital
    ON platform.webhook_endpoints (hospital_id, status);

-- The subscription list. Cascade is correct here: the rows are attributes
-- of the endpoint, not records in their own right.
CREATE TABLE IF NOT EXISTS platform.webhook_endpoint_events (
    endpoint_id UUID        NOT NULL,
    event_type  VARCHAR(40) NOT NULL,

    CONSTRAINT pk_webhook_endpoint_events PRIMARY KEY (endpoint_id, event_type),
    CONSTRAINT fk_webhook_event_endpoint  FOREIGN KEY (endpoint_id)
        REFERENCES platform.webhook_endpoints(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS platform.webhook_deliveries (
    id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    endpoint_id      UUID          NOT NULL,
    event_type       VARCHAR(40)   NOT NULL,
    payload          TEXT          NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts         INT           NOT NULL DEFAULT 0,
    response_status  INT,
    last_error       VARCHAR(2000),
    last_attempt_at  TIMESTAMP,
    sent_at          TIMESTAMP,
    created_by       VARCHAR(255),
    created_at       TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_webhook_deliveries        PRIMARY KEY (id),
    CONSTRAINT fk_webhook_delivery_endpoint FOREIGN KEY (endpoint_id)
        REFERENCES platform.webhook_endpoints(id) ON DELETE CASCADE
);

-- The dispatch sweep: pending rows, oldest first, retry-window filtered.
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_dispatch
    ON platform.webhook_deliveries (status, last_attempt_at);
-- The per-endpoint delivery log (portal drilldown + partner API read).
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_endpoint
    ON platform.webhook_deliveries (endpoint_id, created_at);

-- Widen the credential pointers so the app can encrypt them at rest.
ALTER TABLE platform.organization_platform_services
    ALTER COLUMN api_key_reference TYPE TEXT;
ALTER TABLE platform.hospital_platform_service_links
    ALTER COLUMN credentials_reference TYPE TEXT;
ALTER TABLE platform.department_platform_service_links
    ALTER COLUMN credentials_reference TYPE TEXT;

COMMENT ON TABLE platform.api_keys IS
    'API keys HMS issues to third-party clients (Tier 2 item 45): SHA-256 '
    'hash only, raw shown once; revoked, never deleted.';
COMMENT ON TABLE platform.webhook_deliveries IS
    'Outbound webhook outbox (Tier 2 item 45): thin id-reference payloads, '
    'HMAC-signed, instrument-outbox retry mechanics.';
