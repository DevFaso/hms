-- V65: P1 #4 — CPOE order-set version history
--
-- The existing `admission_order_sets.version` column is just a counter
-- with no history — `incrementVersion()` mutated the same row in place,
-- losing prior content. This migration adds a self-referencing
-- `parent_order_set_id` so each edit can freeze the previous row
-- (active=false) and append a new active row that points at it. The
-- chain forms the version history queryable by the repository.
--
-- Additive only:
--   * NULL parent_order_set_id on existing rows → those rows are v1
--     roots (no prior history to reconstruct).
--   * Foreign key SET NULL on delete to keep the chain query-safe even
--     if an ancestor is hard-deleted.
--   * Index supports the history-chain traversal in the repository.

ALTER TABLE admission_order_sets
    ADD COLUMN IF NOT EXISTS parent_order_set_id UUID;

ALTER TABLE admission_order_sets
    ADD CONSTRAINT fk_admission_order_sets_parent
        FOREIGN KEY (parent_order_set_id)
        REFERENCES admission_order_sets (id)
        ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_admission_order_sets_parent
    ON admission_order_sets (parent_order_set_id);

-- Helper index for the active-by-hospital+name lookup the picker uses.
CREATE INDEX IF NOT EXISTS idx_admission_order_sets_active_hospital_name
    ON admission_order_sets (hospital_id, name)
    WHERE active = true;
