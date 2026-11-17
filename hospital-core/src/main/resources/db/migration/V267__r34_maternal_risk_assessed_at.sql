-- The prenatal planner counts a risk score only if it was made in this pregnancy. updated_at cannot say
-- when: a review of the version moves it, and a new score equal to the old one does not.
-- Existing scores take updated_at, the nearest date they have.
ALTER TABLE clinical.maternal_history
    ADD COLUMN IF NOT EXISTS risk_assessed_at TIMESTAMP WITHOUT TIME ZONE;

UPDATE clinical.maternal_history
    SET risk_assessed_at = updated_at
    WHERE risk_category IS NOT NULL AND risk_assessed_at IS NULL;
