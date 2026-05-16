-- =====================================================================
-- V98: Lab result idempotency for inbound ORU^R01 retransmissions
-- (roadmap row 23, v1.1 / Interop HL7 / "ORU^R01 → LabResult persistence")
--
-- HL7 v2 analyzers (Mindray BS-series, Sysmex XN-series, Roche cobas)
-- retransmit on any timeout, lost TCP segment, or missing ACK. Without
-- a dedup key on the receiver side, a single observation can land
-- multiple times as separate lab_result rows, polluting trends and
-- making the abnormal-flag fire repeatedly.
--
-- MSH-10 (message control id) is the natural key: every HL7 v2 message
-- carries a unique id minted by the sender, and a retransmit uses the
-- SAME id. The same id arriving twice means the same payload — we can
-- short-circuit the second insert and return the first row's outcome.
--
-- Column is nullable + optional (legacy USER-actor writes don't have
-- a source message at all). Partial unique index covers only rows
-- where the id is present, so the existing clinical-entry path is
-- unaffected.
--
-- Strictly additive — every column nullable, every index IF NOT EXISTS.
-- Rollback drops the column + index; the JPA mapping shipped with the
-- rollback omits the field too.
-- =====================================================================

ALTER TABLE lab.lab_results
    ADD COLUMN IF NOT EXISTS source_message_control_id VARCHAR(255) NULL;

-- Partial unique index — only enforce uniqueness on the integration
-- path, NULLs ignored. PG ≥ 9.5 supports partial unique indexes; the
-- project runs PG 16 so we're well clear of the floor.
CREATE UNIQUE INDEX IF NOT EXISTS uk_lab_result_source_message_control_id
    ON lab.lab_results (source_message_control_id)
 WHERE source_message_control_id IS NOT NULL;
