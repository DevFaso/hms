-- V253 (round 19, QA D1096): the paediatric "manual review" becomes a real stop.
-- Below an item's manual-review age (3 months by default, 12 for morphine) or
-- outside every weight band, the dose check gives no automatic verdict and
-- asks for a pharmacist or physician review. It was only a warning, so a
-- 174 mg/kg/day paracetamol order for a 7-week-old signed and reached the
-- pharmacy as an ordinary order. The order now records that a review is
-- required, and who reviewed it, when and why; it is not signed until then.
--
-- Existing rows keep FALSE / NULL: no review was asked of them, and the
-- signature re-runs the dose check on any order still unsigned.
-- Plain statements, no DO block. Idempotent.

ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS pediatric_dose_review_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS pediatric_dose_reviewed_at TIMESTAMP;
ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS pediatric_dose_reviewed_by UUID;
ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS pediatric_dose_review_reason TEXT;

ALTER TABLE clinical.prescriptions
    DROP CONSTRAINT IF EXISTS fk_rx_pediatric_dose_reviewed_by;
ALTER TABLE clinical.prescriptions
    ADD CONSTRAINT fk_rx_pediatric_dose_reviewed_by
        FOREIGN KEY (pediatric_dose_reviewed_by)
        REFERENCES security.users (id)
        ON DELETE SET NULL
        NOT VALID;
