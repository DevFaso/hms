-- V341: QA D2730 (r65, High) - a sensitive category on a lab test definition.
--
-- A result takes its sensitivity from the visit that ordered it. A syphilis
-- or HIV serology ordered in an untagged general consultation carried none,
-- so a caregiver with family access would have seen it. A hospital can now
-- tag the test itself (HIV, REPRODUCTIVE_HEALTH, ...): every result of it is
-- then withheld from a proxy, whatever visit ordered it.
--
-- NULL, the default, keeps today's behaviour. Never seeded: which tests carry
-- a tag is a clinical decision for each hospital.
-- Additive and idempotent: IF NOT EXISTS, no DO block, no backfill.

ALTER TABLE lab.lab_test_definitions
    ADD COLUMN IF NOT EXISTS sensitivity_category VARCHAR(32);
