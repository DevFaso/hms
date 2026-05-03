-- V80: Emergency global controls (MVP-7 — gap #7 in docs/super-admin-gaps.md)
--
-- Adds a singleton config row that holds the global minimum-iat timestamp
-- the JWT filter compares against. Bumping this value to "now" effectively
-- force-logs-out every active session — tokens issued before the new value
-- will be rejected by JwtAuthenticationFilter. Tokens issued after a bump
-- carry an `iat` claim later than the threshold and pass.
--
-- The table is intentionally one-row (id=1, enforced by CHECK + PK). The
-- service layer upserts on id=1.
--
-- Strictly additive — no DROP, no destructive change. Default `epoch`
-- (1970-01-01) means pre-MVP-7 deployments behave identically to MVP-6:
-- every issued JWT trivially passes the iat >= min_iat check.
--
-- Rollback plan: dropping the table reverts to "no global revocation"
-- behaviour — the GlobalSessionRevocationService null-checks the row and
-- treats absence as "iat=epoch" so the filter still works.

CREATE TABLE IF NOT EXISTS security.security_revocations (
    id                          INTEGER     NOT NULL,
    global_min_token_iat        TIMESTAMP   NOT NULL DEFAULT TIMESTAMP 'epoch',
    last_revoked_by_user_id     UUID        NULL,
    last_revoked_by_username    VARCHAR(255) NULL,
    last_revoked_reason         VARCHAR(1000) NULL,
    updated_at                  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_security_revocations PRIMARY KEY (id),
    CONSTRAINT chk_security_revocations_singleton CHECK (id = 1)
);

-- Seed the singleton row so service-layer reads never return empty.
INSERT INTO security.security_revocations (id, global_min_token_iat, updated_at)
SELECT 1, TIMESTAMP 'epoch', CURRENT_TIMESTAMP
 WHERE NOT EXISTS (SELECT 1 FROM security.security_revocations WHERE id = 1);
