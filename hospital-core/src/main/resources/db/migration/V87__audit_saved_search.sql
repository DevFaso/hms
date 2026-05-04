-- V87: Server-side persisted audit saved searches (MVP-c batch — MVP-8c
-- saved-search piece in docs/super-admin-gaps.md).
--
-- Replaces MVP-8b's per-operator localStorage with a server-side store
-- so operators see their saved searches across devices, and so a
-- super admin can mark a search `shared = true` and have peers see it
-- on their list.
--
-- The filter payload is captured as a JSONB blob keyed on the existing
-- AuditSearchFilter shape (eventType list, status, hospital, etc.).
-- We do not denormalise the filter columns: the filter is opaque to
-- this table, evolves with the AuditSearchFilter record, and is round-
-- tripped client → server → client without inspection.
--
-- Strictly additive. No FK to a hard owner-user table because
-- super-admin actors are addressed by username throughout the audit
-- subsystem; the column is indexed for the per-operator listing path.

CREATE TABLE IF NOT EXISTS platform.audit_saved_search (
    id              UUID         PRIMARY KEY,
    owner_username  VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    filter_json     JSONB        NOT NULL,
    shared          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_saved_search_owner
    ON platform.audit_saved_search (owner_username);

CREATE INDEX IF NOT EXISTS idx_audit_saved_search_shared
    ON platform.audit_saved_search (shared)
 WHERE shared = TRUE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_audit_saved_search_owner_name
    ON platform.audit_saved_search (owner_username, name);
