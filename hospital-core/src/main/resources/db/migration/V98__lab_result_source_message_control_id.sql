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
-- HL7 v2 only guarantees MSH-10 (message control id) uniqueness
-- WITHIN a sending system. Two different allowlisted analyzers can
-- legitimately emit the same MSH-10 — those must not collapse into
-- a single row. The dedup key is therefore the composite
-- (MSH-3 sending application, MSH-4 sending facility, MSH-10 control id),
-- not MSH-10 alone. The matching unique index is composite over the
-- same three columns. A retransmit from the same analyzer reuses all
-- three values, so it correctly short-circuits; an unrelated message
-- with a coincidentally-equal MSH-10 from a different analyzer keeps
-- a distinct triplet and lands as a new row.
--
-- Columns are nullable + optional (legacy USER-actor writes don't
-- have a source message at all). Partial unique index covers only
-- rows where the control id is present, so the existing clinical
-- entry path is unaffected.
--
-- Strictly additive — every column nullable, every index IF NOT
-- EXISTS. This migration file defines only the forward schema change;
-- no automated Liquibase rollback is declared here. Operators who
-- need to revert must drop the three columns + index manually and
-- ship a JPA mapping that omits the fields in the same release.
-- =====================================================================

ALTER TABLE lab.lab_results
    ADD COLUMN IF NOT EXISTS source_sending_application VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS source_sending_facility    VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS source_message_control_id  VARCHAR(255) NULL;

-- Composite partial unique index — uniqueness scoped per sender so
-- two analyzers can independently reuse MSH-10 values without
-- collision. Partial (WHERE NOT NULL) so legacy USER-actor rows
-- (all three columns NULL) are excluded from the constraint.
CREATE UNIQUE INDEX IF NOT EXISTS uk_lab_result_source_message
    ON lab.lab_results (source_sending_application,
                        source_sending_facility,
                        source_message_control_id)
 WHERE source_message_control_id IS NOT NULL;
