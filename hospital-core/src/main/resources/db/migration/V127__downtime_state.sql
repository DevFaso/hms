-- V127: platform.platform_downtime_state (P3 item 23a — read-only
-- continuity mode).
--
-- WHY: the platform has no downtime concept. PlatformReleaseWindow rows
-- carry a freeze_changes flag that NOTHING enforces (stored + displayed
-- only), and the emergency broadcast is a fire-and-forget STOMP frame that
-- users who log in after the send never see. This singleton row is the
-- enforced state: an HTTP filter rejects mutating requests while
-- read_only is TRUE, and a persisted GET feeds the portal banner across
-- logins and refreshes.
--
-- Deliberately an HTTP-layer mode, NOT a database-level read-only switch:
-- login itself is a DB write (User.lastLoginAt), and the audit logger
-- swallows persistence failures by design — a DB-level window would break
-- authentication and silently drop the compliance trail.
--
-- Singleton-table pattern copied from V80 security_revocations: one row,
-- id=1 enforced by CHECK + PK, seeded so service reads never return empty.

CREATE TABLE IF NOT EXISTS platform.platform_downtime_state (
    id                    INTEGER       NOT NULL,
    read_only             BOOLEAN       NOT NULL DEFAULT FALSE,
    message               VARCHAR(500),
    activated_at          TIMESTAMP,
    activated_by_username VARCHAR(255),
    updated_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_platform_downtime_state PRIMARY KEY (id),
    CONSTRAINT chk_platform_downtime_singleton CHECK (id = 1)
);

INSERT INTO platform.platform_downtime_state (id, read_only, updated_at)
SELECT 1, FALSE, CURRENT_TIMESTAMP
 WHERE NOT EXISTS (SELECT 1 FROM platform.platform_downtime_state WHERE id = 1);
