-- V223 (QA D660): why a signed prescription was cancelled or discontinued.
-- A signed order can no longer be rewritten by edit; withdrawing it is the
-- only change left, and it must say why. Encrypted by the entity converter
-- (clinical narrative), so TEXT. Plain statements, no DO block.

ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS withdrawal_reason TEXT;
