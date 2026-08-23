-- =====================================================================
-- V131: Multi-OBX ORU^R01 ingestion — one LabResult row per OBX segment
--
-- The MLLP inbound path previously persisted only the FIRST OBX of an
-- ORU^R01 and silently dropped the rest (a 24-panel CBC lost 23
-- observations, including any critical flagged after position 1). The
-- parser/service now fan out one row per OBX, which needs three things
-- at the schema layer:
--
-- 1. source_observation_set_id — OBX-1 (set id) of the segment that
--    produced the row. Every OBX of one message shares the V98 dedup
--    triple (MSH-3, MSH-4, MSH-10), so without a discriminator the
--    unique index would reject the second row of every message. The
--    service guarantees a non-null, per-message-unique value (OBX-1,
--    falling back to the 1-based segment position when OBX-1 is blank
--    or duplicated).
--
-- 2. test_code / reference_range — OBX-3.1 and OBX-7 were parsed and
--    dropped before; with many rows per order they are the only way to
--    tell which analyte a row is. Nullable: the clinical-UI entry path
--    doesn't supply them.
--
-- 3. The V98 unique index redefined to include the discriminator.
--    Legacy rows (first-OBX-only ingestion) are backfilled to set id
--    '1', which is what they factually were. COALESCE in the index
--    keeps the invariant airtight even for a hypothetical null set id.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS, UPDATE only touches NULLs,
-- DROP INDEX IF EXISTS + CREATE INDEX IF NOT EXISTS. Strictly
-- additive apart from the index redefinition, which only widens the
-- key (no existing data can violate it after the backfill).
-- =====================================================================

ALTER TABLE lab.lab_results
    ADD COLUMN IF NOT EXISTS source_observation_set_id VARCHAR(16)  NULL,
    ADD COLUMN IF NOT EXISTS test_code                 VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS reference_range           VARCHAR(255) NULL;

-- Backfill: every pre-V131 MLLP row came from first-OBX-only parsing,
-- so its set id is factually '1'.
UPDATE lab.lab_results
   SET source_observation_set_id = '1'
 WHERE source_message_control_id IS NOT NULL
   AND source_observation_set_id IS NULL;

-- Redefine the V98 dedup index with the per-OBX discriminator. Same
-- name, wider key: a retransmit of the same message still collides on
-- every row (replay short-circuits in the service before insert), but
-- sibling OBX rows of one message no longer collide with each other.
DROP INDEX IF EXISTS lab.uk_lab_result_source_message;
CREATE UNIQUE INDEX IF NOT EXISTS uk_lab_result_source_message
    ON lab.lab_results (source_sending_application,
                        source_sending_facility,
                        source_message_control_id,
                        COALESCE(source_observation_set_id, '1'))
 WHERE source_message_control_id IS NOT NULL;
