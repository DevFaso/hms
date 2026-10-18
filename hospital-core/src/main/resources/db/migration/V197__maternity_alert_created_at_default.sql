-- QA D131: abnormal postpartum observations and newborn assessments failed to
-- save because the embedded alert rows were inserted with a NULL created_at
-- (an @Embeddable never receives @PrePersist). The entities now stamp the
-- value; this default is the database-side guard, matching the labour and
-- delivery alert tables.
ALTER TABLE clinical.postpartum_observation_alerts ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE clinical.newborn_assessment_alerts ALTER COLUMN created_at SET DEFAULT now();
