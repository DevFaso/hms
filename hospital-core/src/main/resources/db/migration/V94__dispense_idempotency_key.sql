-- V94: v1.0 / Pharmacy / T-68 offline dispense queue (roadmap row 4)
--
-- Adds an OPTIONAL idempotency key to clinical.dispenses so a pharmacist
-- working from the offline queue can replay the same POST /pharmacy/dispense
-- request after connectivity returns without producing two dispense rows
-- (and two stock decrements, and two ready-for-pickup SMS).
--
-- Generation contract — the frontend mints the key at "user clicks Dispense":
--   <userId>-<prescriptionId>-<isoTimestampWithNanos>
-- => stable across retries of the same logical action, distinct across two
--    deliberate dispenses of the same prescription seconds apart.
--
-- Resolution contract (DispenseServiceImpl.createDispense):
--   1. If the request omits the key  → behave exactly as today (no dedup).
--   2. If the key is present and a row already exists → return the existing
--      DispenseResponseDTO and skip every side-effect (stock, audit, SMS).
--   3. If the key is present and no row exists → save the dispense WITH the
--      key. The UNIQUE index below makes a concurrent second insert fail
--      with a constraint-violation that the service translates into the
--      same "return existing" path.
--
-- Additive + idempotent: the column allows NULL so historical dispenses
-- and any future caller that does not opt in keep working unchanged.
--
-- splitStatements MAY be true here — every statement below is a single SQL
-- DDL. We still wrap the column add and index creation in IF NOT EXISTS
-- guards so re-running the migration on an environment where it already
-- ran (e.g. a dev rolled back to develop after merging this) is a no-op.

ALTER TABLE clinical.dispenses
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

-- UNIQUE index, not constraint, so we can use a partial index that ignores
-- the NULL rows — Postgres treats NULLs as distinct in a UNIQUE constraint
-- but builds them into the index, which inflates it pointlessly when the
-- key is opt-in.
CREATE UNIQUE INDEX IF NOT EXISTS uq_disp_idempotency_key
    ON clinical.dispenses (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Lightweight comment so a DBA inspecting the column with \d+ in psql sees
-- where the contract is documented without grepping the codebase.
COMMENT ON COLUMN clinical.dispenses.idempotency_key IS
    'Roadmap row 4 / T-68 offline dispense queue. Optional client-supplied key '
    'that makes POST /pharmacy/dispense idempotent for replay. NULL on legacy / '
    'opted-out callers.';
