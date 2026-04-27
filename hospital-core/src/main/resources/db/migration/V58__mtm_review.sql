-- P-09: Medication Therapy Management (MTM) review record.
-- Captures pharmacist-led chronic disease review, adherence counselling,
-- polypharmacy alerts, and intervention records.
--
-- Rollback:
--   DROP TABLE IF EXISTS clinical.mtm_reviews;

CREATE TABLE IF NOT EXISTS clinical.mtm_reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id UUID NOT NULL REFERENCES clinical.patients(id),
    hospital_id UUID NOT NULL REFERENCES hospital.hospitals(id),
    pharmacist_user_id UUID NOT NULL REFERENCES security.users(id),
    review_date TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    chronic_condition_focus VARCHAR(500),
    adherence_concern BOOLEAN NOT NULL DEFAULT FALSE,
    polypharmacy_alert BOOLEAN NOT NULL DEFAULT FALSE,
    intervention_summary VARCHAR(2000),
    recommended_actions VARCHAR(2000),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    follow_up_date DATE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_mtm_patient ON clinical.mtm_reviews(patient_id);
CREATE INDEX IF NOT EXISTS idx_mtm_hospital ON clinical.mtm_reviews(hospital_id);
CREATE INDEX IF NOT EXISTS idx_mtm_pharmacist ON clinical.mtm_reviews(pharmacist_user_id);
CREATE INDEX IF NOT EXISTS idx_mtm_status ON clinical.mtm_reviews(status);
CREATE INDEX IF NOT EXISTS idx_mtm_review_date ON clinical.mtm_reviews(review_date);
