-- V299 (r54, provisional severe-hypertension rule): the signs of acute organ
-- damage a recorder may tick next to a blood pressure (chest pain, neurological
-- signs, dyspnoea, visual disturbance, severe headache), stored as
-- OrganDamageSymptom names, comma-separated. One ticked sign turns an adult's
-- severe-range warning (>= 180 / >= 120 mmHg) URGENT on vitals entry and on the
-- doctor's BPA panel. Optional: NULL means none was ticked, and no existing row
-- is touched. Idempotent.

ALTER TABLE clinical.patient_vital_signs
    ADD COLUMN IF NOT EXISTS organ_damage_symptoms VARCHAR(200);
