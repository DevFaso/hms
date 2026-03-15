-- V15: Add clinical.medication_administration_records
-- Tracks each nursing medication administration event linked to a Prescription.
-- Statuses: PENDING | GIVEN | HELD | REFUSED | MISSED

CREATE TABLE IF NOT EXISTS clinical.medication_administration_records (
    id                        UUID              PRIMARY KEY DEFAULT gen_random_uuid(),
    prescription_id           UUID              NOT NULL
        REFERENCES clinical.prescriptions(id) ON DELETE CASCADE,
    patient_id                UUID              NOT NULL
        REFERENCES clinical.patients(id) ON DELETE CASCADE,
    hospital_id               UUID              NOT NULL
        REFERENCES hospital.hospitals(id) ON DELETE CASCADE,
    administered_by_staff_id  UUID
        REFERENCES hospital.staff(id) ON DELETE SET NULL,
    medication_name           VARCHAR(255)      NOT NULL,
    dose                      VARCHAR(100),
    route                     VARCHAR(80),
    scheduled_time            TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    administered_at           TIMESTAMP WITHOUT TIME ZONE,
    status                    VARCHAR(20)       NOT NULL DEFAULT 'PENDING',
    reason                    VARCHAR(1024),
    notes                     VARCHAR(2000),
    created_at                TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at                TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_mar_prescription
    ON clinical.medication_administration_records(prescription_id);

CREATE INDEX IF NOT EXISTS idx_mar_patient
    ON clinical.medication_administration_records(patient_id);

CREATE INDEX IF NOT EXISTS idx_mar_hospital
    ON clinical.medication_administration_records(hospital_id);

CREATE INDEX IF NOT EXISTS idx_mar_nurse
    ON clinical.medication_administration_records(administered_by_staff_id);

CREATE INDEX IF NOT EXISTS idx_mar_scheduled
    ON clinical.medication_administration_records(scheduled_time);

CREATE INDEX IF NOT EXISTS idx_mar_status
    ON clinical.medication_administration_records(status);
