-- QA D224: the after-visit summary lost its prescription and referral summaries
-- and the patient-education text after checkout, because they lived only on
-- the checkout request. They are now kept on the encounter's discharge summary
-- (education already had patient_education_provided). Nullable, no default:
-- rows written before this migration simply have no text, as they had none.
ALTER TABLE clinical.discharge_summaries
    ADD COLUMN IF NOT EXISTS prescription_summary VARCHAR(3000),
    ADD COLUMN IF NOT EXISTS referral_summary VARCHAR(3000);
