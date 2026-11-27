-- QA r81 D4190: withdrawing a medicine from the catalogue left no line in the entry's history. A withdrawal is now
-- one more row of that history: who withdrew it, when and why. It takes effect at once, by one person, so it is
-- neither a proposal nor an approval: its own change type and its own status.
ALTER TABLE clinical.medication_catalog_versions DROP CONSTRAINT IF EXISTS ck_med_catalog_version_change;
ALTER TABLE clinical.medication_catalog_versions ADD CONSTRAINT ck_med_catalog_version_change
    CHECK (change_type IN ('CREATE', 'UPDATE', 'RATIFY', 'WITHDRAWAL'));
ALTER TABLE clinical.medication_catalog_versions DROP CONSTRAINT IF EXISTS ck_med_catalog_version_status;
ALTER TABLE clinical.medication_catalog_versions ADD CONSTRAINT ck_med_catalog_version_status
    CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'SUPERSEDED', 'WITHDRAWN', 'RECORDED'));
-- The two go together: only a withdrawal is recorded without a decision, and a withdrawal is nothing else.
ALTER TABLE clinical.medication_catalog_versions DROP CONSTRAINT IF EXISTS ck_med_catalog_version_withdrawal;
ALTER TABLE clinical.medication_catalog_versions ADD CONSTRAINT ck_med_catalog_version_withdrawal
    CHECK ((change_type = 'WITHDRAWAL') = (status = 'RECORDED'));
